(ns hyperion.sql.jdbc
  (:use
    [hyperion.key :only [generate-id]]
    [hyperion.sql.connection :only [connection]]
    [hyperion.sql.query :only [query-str params]]
    [hyperion.sql.query-builder]))

(defn result-set->seq [rs]
  (let [rsmeta (.getMetaData rs)
        idxs (range 1 (inc (.getColumnCount rsmeta)))
        columns (map #(.getColumnLabel rsmeta %) idxs)
        values (fn [] (map (fn [i] (.getObject rs i)) idxs))
        result (java.util.ArrayList.)]
      (while (.next rs)
        (.add result (zipmap columns (values))))
    result))

(defprotocol SetObject
  (set-object [this stmt index]))

(extend-protocol SetObject
  clojure.lang.Keyword
  (set-object [this stmt index]
    (set-object (name this) stmt index))

  java.math.BigDecimal
  (set-object [this stmt index]
    (.setBigDecimal stmt index this))

  java.lang.Boolean
  (set-object [this stmt index]
    (.setBoolean stmt index this))

  java.util.Date
  (set-object [this stmt index]
    (set-object (java.sql.Date. (.getTime this)) stmt index))

  java.sql.Date
  (set-object [this stmt index]
    (.setDate stmt index this))

  java.lang.Double
  (set-object [this stmt index]
    (.setDouble stmt index this))

  java.lang.Float
  (set-object [this stmt index]
    (.setFloat stmt index this))

  java.math.BigInteger
  (set-object [this stmt index]
    (set-object (java.math.BigDecimal. this) stmt index))

  java.lang.Integer
  (set-object [this stmt index]
    (.setInt stmt index this))

  java.lang.Long
  (set-object [this stmt index]
    (.setLong stmt index this))

  nil
  (set-object [this stmt index]
    (.setObject stmt index this))

  java.lang.String
  (set-object [this stmt index]
    (.setString stmt index this))

  java.sql.Time
  (set-object [this stmt index]
    (.setTime stmt index this))

  java.sql.Timestamp
  (set-object [this stmt index]
    (.setTimestamp stmt index this)))

(defn- set-parameters [stmt params]
  (dorun
    (map-indexed
      (fn [ix value]
        (set-object value stmt (inc ix)))
      params)))

(defn- prepare-statement [query-str]
  (try
    (.prepareStatement (connection) query-str java.sql.Statement/RETURN_GENERATED_KEYS)
    (catch Exception e
      (.prepareStatement (connection) query-str))))

(defn execute-write [query]
  (with-open [stmt (prepare-statement (query-str query))]
    (set-parameters stmt (params query))
    (.executeUpdate stmt)
    (with-open [result-set (.getGeneratedKeys stmt)]
      (first (result-set->seq result-set)))))

(defn execute-query [query]
  (with-open [stmt (.prepareStatement (connection) (query-str query) java.sql.ResultSet/TYPE_FORWARD_ONLY, java.sql.ResultSet/CONCUR_READ_ONLY)]
    (set-parameters stmt (params query))
    (with-open [result-set (.executeQuery stmt)]
      (result-set->seq result-set))))

(defn execute-mutation [query]
  (with-open [stmt (.prepareStatement (connection) (query-str query))]
    (set-parameters stmt (params query))
    (.executeUpdate stmt)))

(defmacro without-auto-commit [& body]
  `(let [conn# (connection)
         old-auto-commit# (.getAutoCommit conn#)]
     (.setAutoCommit conn# false)
     (try
       ~@body
       (finally
         (.setAutoCommit conn# old-auto-commit#)))))

(def ^{:private true :dynamic true} *in-txn* false)

(defn exec [query]
  (let [stmt (.createStatement (connection))]
    (.executeUpdate stmt query)
    (.close stmt)))

(defn new-savepoint-id []
  (str "JDBC_SPID_" (generate-id)))

(defn- begin-savepoint []
  (let [savepoint-id (new-savepoint-id)]
    (exec (str "SAVEPOINT " savepoint-id))
    savepoint-id))

(defn- rollback-to-savepoint [savepoint-id]
  (exec (str "ROLLBACK TO SAVEPOINT " savepoint-id)))

(defn- release-savepoint [savepoint-id]
  (exec (str "RELEASE SAVEPOINT " savepoint-id)))

(defn with-savepoint [f]
  (if *in-txn*
    (f (begin-savepoint))
    (binding [*in-txn* true]
      (without-auto-commit
        (try
          (let [result (f (begin-savepoint))]
            (.commit (connection))
            result)
          (catch Exception e
            (.rollback (connection))
            (throw e)))))))

(defn transaction-fn [f]
  (with-savepoint
    (fn [savepoint-id]
      (try
        (let [result (f)]
          (release-savepoint savepoint-id)
          result)
        (catch Exception e
          (rollback-to-savepoint savepoint-id)
          (throw e))))))

(defmacro transaction [& body]
  `(transaction-fn (fn [] ~@body)))

(defn rollback-fn [f]
  (with-savepoint
    (fn [savepoint-id]
      (try
        (f)
        (finally
          (rollback-to-savepoint savepoint-id))))))

(defmacro rollback [& body]
  `(rollback-fn (fn [] ~@body)))
