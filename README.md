# VevDB for Clojure

This is the Clojure package layer for VevDB. It depends on
[`vev-java`](https://github.com/vevdb/vev-java), which uses Java 25 Foreign
Function & Memory to call the native VevDB engine.

The public API accepts ordinary Clojure data and serializes it to the same EDN
text frontend used by C, Python, Rust, and Java callers.

## Usage

Normal application setup is one dependency. The Clojure package pulls in the
Java wrapper, whose release jar contains the native engines:

```clojure
{:deps {com.vevdb/vev-clj {:mvn/version "0.2.0-rc.2"}}}
```

The `0.2.0-rc.2` artifacts are available from
[Maven Central](https://central.sonatype.com/artifact/com.vevdb/vev-clj/0.2.0-rc.2)
and the
[VevDB prerelease](https://github.com/vevdb/vev/releases/tag/v0.2.0-rc.2).
The earlier `v0.1.0-rc.3` artifact used the provisional `dev.vevdb` coordinate
and Java package.

The source repository can be consumed through a Git coordinate:

```clojure
{:deps
 {com.vevdb/vev-clj
  {:git/url "https://github.com/vevdb/vev-clj"
   :git/tag "<release-tag>"
   :git/sha "<release-sha>"}}}
```

Its `deps.edn` brings in the `vev-java` artifact, which contains the native
VevDB engine. Both forms therefore provide the same self-contained runtime
from Maven Central.

Application code should not pass Java paths or native library paths around:

```clojure
(require '[vev.core :as d])

(def conn (d/create-conn))

(d/transact conn
  [{:db/id 1
    :user/name "Ada"
    :user/email "ada@example.com"}])

(def db (d/db conn))

(d/q
  '[:find ?name
    :where [?e :user/name ?name]]
  db)
```

Transaction listeners are report callbacks on successful commits:

```clojure
(def listener
  (d/listen conn :audit
    (fn [report]
      (println (:tx-data report)))))

(d/transact conn [{:db/id 2 :user/name "Grace"}])
(d/unlisten conn listener)
```

The same `d/listen` / `d/unlisten` functions work for durable connections
opened with `d/connect`; failed transactions do not notify listeners.

Durable usage should be similarly direct:

```clojure
(def conn (d/connect "app.vev"))

(d/transact conn [{:db/id 1 :user/name "Ada"}])
(d/q '[:find ?name :where [?e :user/name ?name]] (d/db conn))
```

Historical database filters use the Datomic names, argument order, and
inclusive/exclusive boundaries. `#inst` is a `java.util.Date`, so the same form
works with Datomic Peer and Vev:

```clojure
(def current (d/db conn))
(d/as-of current tx) ; inclusive
(d/as-of current #inst "2026-07-20T10:15:00.000Z")
(d/since current #inst "2026-07-20T10:15:00.000Z") ; exclusive
(d/history current)

(d/basis-t current)
(d/next-t current)
(d/as-of-t (d/as-of current tx))
(d/since-t (d/since current tx))
(d/history? (d/history current))

;; Log range start is inclusive and end is exclusive.
(d/tx-range (d/log conn) nil nil)
(d/tx-range (d/log conn) tx-start tx-end)
(d/tx-range (d/log conn) #inst "2026-07-20" #inst "2026-07-21")
```

`java.time.Instant` is also accepted by Vev. The
[history guide](https://github.com/vevdb/vev/blob/main/docs/history.md)
includes an executable side-by-side comparison with Datomic Peer.

For bulk host writes, `tx-builder` can be passed to the same `d/transact`
function on either in-memory or durable connections:

```clojure
(def tx (d/tx-builder conn 2))
(d/tx-add! tx 2 :user/name "Grace")
(d/tx-add! tx 2 :user/email "grace@example.com")
(d/transact conn tx)
(.close tx)
```

The durable backend uses SQLite internally. Release builds link SQLite with
FTS5 into the native VevDB library, so Clojure users do not install or configure
SQLite; application code opens a VevDB store with `d/connect`.

## Local Development

Check `vev`, `vev-java`, and `vev-clj` out beside one another. Install the Java
source artifact, then use this repository directly:

```sh
cd ../vev-java
mvn install
cd ../vev-clj
clojure -P
```

For runtime work against a locally built engine:

```sh
cd ../vev
scripts/build_c_abi.sh
export VEV_LIB="$PWD/build/lib/libvev.dylib" # macOS example
cd ../vev-clj
clojure -M:dev
```

The `:dev` alias enables Java 25 native access. Released
artifacts resolve the engine from their bundled platform resource; source
development can use `vev.library` or `VEV_LIB`.

`q` uses Datomic/DataScript-style query-first argument order. The wrapper also
accepts DB-first calls for compatibility with earlier VevDB code, but new code
should follow the familiar query-then-sources shape:

```clojure
(d/q '[:find ?name :where [?e :user/name ?name]] db)
```

For Datomic `d/query`-style host code, use `query` with a request map:

```clojure
(d/query
  {:query '[:find ?name
            :in $ ?email
            :where [?e :user/email ?email]
                   [?e :user/name ?name]]
   :args [db "ada@example.com"]})
```

Return-map markers produce Clojure maps:

```clojure
(d/q
  '[:find ?name ?email
    :keys name email
    :where [?e :user/name ?name]
           [?e :user/email ?email]]
  db)
;; => #{{:name "Ada", :email "ada@example.com"}}
```

Inputs are passed as ordinary arguments after the query and DB:

```clojure
(d/q
  '[:find ?name
    :in $ [?email ...]
    :where [?e :user/email ?email]
           [?e :user/name ?name]]
  db
  ["ada@example.com" "grace@example.com"])
```

Plain `q`/`rows` calls prepare a temporary native query handle and close it after
the call. Use `prepare` when the same query should be reused:

```clojure
(def email-query
  (d/prepare conn
    '[:find ?e ?email
      :in $ ?needle
      :where [?e :user/email ?email]
             [(= ?email ?needle)]]))

(d/prepared-edn email-query)
(d/q email-query db "ada@example.com")
```

Single where clauses can be parsed directly for DataScript-style parser tooling:

```clojure
(d/parse-clause conn '[?e :user/email ?email])
```

`prepared-edn` also works for reusable pull patterns:

```clojure
(def person-pattern
  (d/prepare-pull-pattern db
    [:user/name {:user/friend [:user/name]}]))

(d/prepared-edn person-pattern)
(d/pull db person-pattern 1)
```

Pull follows the same DB-value shape:

```clojure
(d/pull db
  [:user/name {:user/friend [:user/name]}]
  1)

(d/pull-many db [:user/name] [1 2])
```

Entity views also follow the DB-value shape. They are backed by the immutable
DB snapshot, so later transactions on the connection do not change what the
view sees:

```clojure
(let [db (d/db conn)
      ada (d/entity db 1)]
  (:user/name ada)
  (d/entity-values ada :user/email)
  (d/entity-ref ada :user/friend)
  (d/touch ada))
```

Lookup refs and idents are supported through the same function:

```clojure
(d/entity db [:user/email "ada@example.com"])
(d/entity db :user/ada)
```

Transaction functions follow Datomic's installed-ident model: the DB contains
the function ident, while the host registry supplies the executable callback for
this process.

```clojure
(with-open [fns (d/tx-fns conn
                  {:user/set-name
                   (fn [db e name]
                     [[:db/add e :user/name name]])})]
  (d/transact conn [[:db/add 100 :db/ident :user/set-name]])
  (d/transact conn [[:user/set-name 1 "Ada"]] fns))
```

The same shape works with `d/connect` durable handles. The callback receives
`(db & args)` and returns ordinary tx-data. The DB value is valid for the
callback call; keep durable application state outside the callback if it needs
to outlive the transaction.

Immutable DB values support Datomic/DataScript-style `with` operations:

```clojure
(let [report (d/with db [{:db/id 3 :user/name "Barbara"}])
      next-db (d/db-with db [{:db/id 3 :user/name "Barbara"}])]
  [(:ok report)
   (d/q '[:find ?e :where [?e :user/name "Barbara"]] db)
   (d/q '[:find ?e :where [?e :user/name "Barbara"]] next-db)])
```

A mutable connection can also be initialized from an immutable DB snapshot:

```clojure
(def next-conn (d/conn-from-db next-db))

(d/transact next-conn [{:db/id 4 :user/name "Dorothy"}])
(d/q '[:find ?name :where [?e :user/name ?name]] (d/db next-conn))
```

Durable connections use the same transaction and DB-value query shape:

```clojure
(def durable (d/connect "app.vev"))

(d/connection-info durable)
;; => {:backend :sqlite, :path "app.vev", :basis-t 0, :tx-count 0, :tx-ids []}

(d/transact durable [{:db/id 1 :user/name "Ada"}])
(d/q '[:find ?name :where [?e :user/name ?name]] (d/db durable))
```

For explicit durable bulk ingest, native builders can be committed as one
ordinary transaction:

```clojure
(with-open [first (d/tx-builder durable 1)
            second (d/tx-builder durable 1)]
  (d/tx-add! first 2 :user/name "Grace")
  (d/tx-add! second 3 :user/name "Hedy")
  (d/transact-bulk durable [first second]))
```

DB snapshots are immutable values and use JVM-managed cleanup for their native
handles. Use them like Datomic DB values; they do not normally need
`with-open`. Explicitly closing a DB snapshot is only useful when a tight loop
needs deterministic release.

Connections and explicitly allocated helpers such as transaction builders,
function registries, prepared queries, and prepared pull patterns own
long-lived resources. Close those when their application lifecycle ends;
`with-open` remains useful at those explicit resource boundaries.

The current package is deliberately thin:

- `transact` and `with` return transaction report maps from typed native report handles
- `transact-text` and `with-text` return raw EDN report strings
- `q` returns a set of row vectors
- `rows` returns an ordered vector of row vectors
- entity ids are converted to integers
- pull maps are converted to Clojure maps
- immutable DB snapshots are ordinary passable values with JVM-managed cleanup;
  they still implement `AutoCloseable` for optional deterministic cleanup

Run through the top-level ABI smoke script:

```sh
scripts/build_c_abi.sh
```
