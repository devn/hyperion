(ns hyperion.sql.fake-driver)

(deftype FakeConnection []
  java.sql.Connection
  )

(deftype FakeDriver []
  java.sql.Driver
  (acceptsURL [this url] (.startsWith url "jdbc:fake"))
  (connect [this url info] (when (.acceptsURL this url) (FakeConnection.)))
  (getMajorVersion [this])
  (getMinorVersion [this])
  (getPropertyInfo [this url info])
  (jdbcCompliant [this])
  )

;(java.sql.DriverManager/setLogWriter (java.io.PrintWriter. System/out))

(let [driver (FakeDriver.)]
  (java.sql.DriverManager/deregisterDriver driver)
  (java.sql.DriverManager/registerDriver driver))


