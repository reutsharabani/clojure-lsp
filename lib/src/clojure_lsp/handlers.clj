(ns clojure-lsp.handlers
  (:require
   [clojure-lsp.clojure-producer :as clojure-producer]
   [clojure-lsp.crawler :as crawler]
   [clojure-lsp.db :as db]
   [clojure-lsp.dep-graph :as dep-graph]
   [clojure-lsp.feature.call-hierarchy :as f.call-hierarchy]
   [clojure-lsp.feature.clojuredocs :as f.clojuredocs]
   [clojure-lsp.feature.code-actions :as f.code-actions]
   [clojure-lsp.feature.code-lens :as f.code-lens]
   [clojure-lsp.feature.completion :as f.completion]
   [clojure-lsp.feature.diagnostics :as f.diagnostic]
   [clojure-lsp.feature.document-symbol :as f.document-symbol]
   [clojure-lsp.feature.file-management :as f.file-management]
   [clojure-lsp.feature.format :as f.format]
   [clojure-lsp.feature.hover :as f.hover]
   [clojure-lsp.feature.java-interop :as f.java-interop]
   [clojure-lsp.feature.linked-editing-range :as f.linked-editing-range]
   [clojure-lsp.feature.refactor :as f.refactor]
   [clojure-lsp.feature.rename :as f.rename]
   [clojure-lsp.feature.semantic-tokens :as f.semantic-tokens]
   [clojure-lsp.feature.signature-help :as f.signature-help]
   [clojure-lsp.feature.stubs :as stubs]
   [clojure-lsp.feature.workspace-symbols :as f.workspace-symbols]
   [clojure-lsp.kondo :as lsp.kondo]
   [clojure-lsp.parser :as parser]
   [clojure-lsp.queries :as q]
   [clojure-lsp.settings :as settings]
   [clojure-lsp.shared :as shared]
   [clojure.core.async :as async]
   [clojure.pprint :as pprint]
   [lsp4clj.protocols.logger :as logger]
   [lsp4clj.protocols.producer :as producer]))

(set! *warn-on-reflection* true)

;; e.g. 2^0, 2^1, ..., up to 200ms
(def backoff-start 5)
(def backoff-mult 1.2)
(def backoff-max 200)
(comment
  (->> backoff-start
       (iterate #(min backoff-max (Math/ceil (* backoff-mult %))))
       (reductions +)
       (take 15)))

(defmacro process-after-changes [task-id uri & body]
  (let [waiting-start-sym (gensym "waiting-start-time")
        start-sym (gensym "start-time")
        backoff-sym (gensym "backoff")
        process-msg (str task-id " %s")
        wait-and-process-msg (str task-id " %s - waited %s")]
    `(let [~waiting-start-sym (System/nanoTime)]
       (loop [~backoff-sym backoff-start]
         (if (> (quot (- (System/nanoTime) ~waiting-start-sym) 1000000) 60000) ; one minute timeout
           ~(with-meta
              `(logger/warn (format "Timeout in %s waiting for changes to %s" ~task-id ~uri))
              (meta &form))
           (if (contains? (:processing-changes @db/db*) ~uri)
             (do
               (Thread/sleep ~backoff-sym)
               (recur (min backoff-max (* backoff-mult ~backoff-sym))))
             (let [~start-sym (System/nanoTime)
                   result# (do ~@body)]
               ~(with-meta
                  `(logger/info
                     (if (= backoff-start ~backoff-sym)
                       (format ~process-msg (shared/start-time->end-time-ms ~waiting-start-sym))
                       (format ~wait-and-process-msg
                               (shared/start-time->end-time-ms ~start-sym)
                               (shared/format-time-delta-ms ~waiting-start-sym ~start-sym))))
                  (meta &form))
               result#)))))))

(defn ^:private analyze-test-paths! [{:keys [db* producer]}]
  (clojure-producer/refresh-test-tree producer (dep-graph/internal-uris @db*)))

(defn initialize
  [project-root-uri
   client-capabilities
   client-settings
   work-done-token
   {:keys [db* producer] :as components}]
  (shared/logging-task
    :initialize
    (swap! db* assoc :project-analysis-type :project-and-deps)
    (if project-root-uri
      (do
        (crawler/initialize-project
          project-root-uri
          client-capabilities
          client-settings
          {}
          work-done-token
          components)
        (let [db @db*]
          (when (settings/get db [:lint-project-files-after-startup?] true)
            (async/go
              ;; TODO: this starts publishing diagnostics before we've responded
              ;; to "initialize", which is invalid according to the spec
              ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize
              (f.diagnostic/publish-all-diagnostics! (-> db :settings :source-paths) db)))
          (async/go
            (f.clojuredocs/refresh-cache! db*))
          (async/go
            (let [settings (:settings db)]
              (when (stubs/check-stubs? settings)
                (stubs/generate-and-analyze-stubs! settings db*))))
          (async/go
            (logger/info crawler/startup-logger-tag "Analyzing test paths for project root" project-root-uri)
            (analyze-test-paths! components))
          (when (settings/get db [:java] true)
            (async/go
              (f.java-interop/retrieve-jdk-source-and-analyze! db*)))))
      (producer/show-message producer "No project-root-uri was specified, some features may not work properly." :warning nil))))

(defn did-open [{:keys [text-document]} {:keys [producer db*]}]
  (shared/logging-task
    :did-open
    (let [uri (:uri text-document)
          text (:text text-document)]
      (f.file-management/did-open uri text db* true)
      (clojure-producer/refresh-test-tree producer [uri])))
  nil)

(defn did-save [{:keys [text-document]}]
  (shared/logging-task
    :did-save
    (f.file-management/did-save text-document db/db*)))

;; TODO implement it, do we need to do anything?
#_(defn did-delete-files [{:keys [text-document]}]
    (when (get-in @db/db [:documents text-document :saved-on-disk])
      (swap! db/db #(update % :documents dissoc text-document))))

(defn did-change [{:keys [text-document content-changes]}]
  (f.file-management/did-change (:uri text-document) content-changes (:version text-document) db/db*))

(defn did-close [{:keys [text-document]}]
  (shared/logging-task
    :did-close
    (f.file-management/did-close text-document db/db*)))

(defn did-change-watched-files [{:keys [changes]}]
  (f.file-management/did-change-watched-files changes db/db*))

(defn completion [{:keys [text-document position]}]
  (shared/logging-results
    (str :completion " %s - total items: %s")
    count
    (let [db @db/db*
          row (-> position :line inc)
          col (-> position :character inc)]
      (f.completion/completion text-document row col db))))

(defn references [{:keys [text-document position context]} {:keys [db*]}]
  (shared/logging-task
    :references
    (let [db @db*
          row (-> position :line inc)
          col (-> position :character inc)]
      (mapv (fn [reference]
              {:uri (-> (:filename reference)
                        (shared/filename->uri db)
                        (f.java-interop/uri->translated-uri db))
               :range (shared/->range reference)})
            (q/find-references-from-cursor db (shared/uri->filename text-document) row col (:include-declaration context))))))

(defn completion-resolve-item [item {:keys [db*]}]
  (shared/logging-task
    :resolve-completion-item
    (f.completion/resolve-item item db*)))

(defn prepare-rename [{:keys [text-document position]}]
  (shared/logging-task
    :prepare-rename
    (let [[row col] (shared/position->line-column position)]
      (f.rename/prepare-rename text-document row col @db/db*))))

(defn rename [{:keys [text-document position new-name]}]
  (shared/logging-task
    :rename
    (let [[row col] (shared/position->line-column position)]
      (f.rename/rename-from-position text-document new-name row col @db/db*))))

(defn definition [{:keys [text-document position]} {:keys [db*]}]
  (shared/logging-task
    :definition
    (let [db @db*
          [line column] (shared/position->line-column position)]
      (when-let [definition (q/find-definition-from-cursor db (shared/uri->filename text-document) line column)]
        {:uri (-> (:filename definition)
                  (shared/filename->uri db)
                  (f.java-interop/uri->translated-uri db))
         :range (shared/->range definition)}))))

(defn declaration [{:keys [text-document position]} {:keys [db*]}]
  (shared/logging-task
    :declaration
    (let [db @db*
          [line column] (shared/position->line-column position)]
      (when-let [declaration (q/find-declaration-from-cursor db (shared/uri->filename text-document) line column)]
        {:uri (-> (:filename declaration)
                  (shared/filename->uri db)
                  (f.java-interop/uri->translated-uri db))
         :range (shared/->range declaration)}))))

(defn implementation [{:keys [text-document position]} {:keys [db*]}]
  (shared/logging-task
    :implementation
    (let [db @db*
          [row col] (shared/position->line-column position)]
      (mapv (fn [implementation]
              {:uri (-> (:filename implementation)
                        (shared/filename->uri db)
                        (f.java-interop/uri->translated-uri db))
               :range (shared/->range implementation)})
            (q/find-implementations-from-cursor db (shared/uri->filename text-document) row col)))))

(defn document-symbol [{:keys [text-document]}]
  (shared/logging-task
    :document-symbol
    (let [db @db/db*
          filename (shared/uri->filename text-document)
          namespace-definition (q/find-namespace-definition-by-filename db filename)]
      [{:name (or (some-> namespace-definition :name name)
                  filename)
        :kind (f.document-symbol/element->symbol-kind namespace-definition)
        :range shared/full-file-range
        :selection-range (if namespace-definition
                           (shared/->scope-range namespace-definition)
                           shared/full-file-range)
        :children (->> (q/find-var-definitions db filename true)
                       (mapv (fn [e]
                               (shared/assoc-some
                                 {:name (-> e :name name)
                                  :kind (f.document-symbol/element->symbol-kind e)
                                  :range (shared/->scope-range e)
                                  :selection-range (shared/->range e)
                                  :tags (cond-> []
                                          (:deprecated e) (conj 1))}
                                 :detail (when (:private e)
                                           "private")))))}])))

(defn document-highlight [{:keys [text-document position]}]
  (process-after-changes
    :document-highlight text-document
    (let [db @db/db*
          line (-> position :line inc)
          column (-> position :character inc)
          filename (shared/uri->filename text-document)
          local-db (update db :analysis select-keys [filename])
          references (q/find-references-from-cursor local-db filename line column true)]
      (mapv (fn [reference]
              {:range (shared/->range reference)})
            references))))

(defn workspace-symbols [{:keys [query]}]
  (shared/logging-task
    :workspace-symbol
    (f.workspace-symbols/workspace-symbols query @db/db*)))

(defn ^:private server-info []
  (let [db-value @db/db*]
    {:project-root-uri (:project-root-uri db-value)
     :project-settings (:project-settings db-value)
     :classpath-settings (:classpath-settings db-value)
     :classpath (:classpath db-value)
     :client-settings (:client-settings db-value)
     :final-settings (settings/all db-value)
     :cljfmt-raw (binding [*print-meta* true]
                   (pr-str (f.format/resolve-user-cljfmt-config db-value)))
     :port (or (:port db-value)
               "NREPL only available on :debug profile (`make debug-cli`)")
     :server-version (shared/clojure-lsp-version)
     :clj-kondo-version (lsp.kondo/clj-kondo-version)
     :log-path (:log-path db-value)}))

(defn server-info-log [{:keys [producer]}]
  (shared/logging-task
    :server-info-log
    (producer/show-message
      producer
      (with-out-str (pprint/pprint (server-info)))
      :info
      nil)))

(def server-info-raw #'server-info)

(defn ^:private cursor-info [[doc-id line character]]
  (let [db @db/db*
        elements (q/find-all-elements-under-cursor db (shared/uri->filename doc-id) (inc line) (inc character))]
    (shared/assoc-some {}
                       :elements (mapv (fn [e]
                                         (shared/assoc-some
                                           {:element e}
                                           :definition (q/find-definition db e)
                                           :semantic-tokens (f.semantic-tokens/element->token-type e)))
                                       elements))))

(defn cursor-info-log [{:keys [text-document position]} {:keys [producer]}]
  (shared/logging-task
    :cursor-info-log
    (producer/show-message
      producer
      (with-out-str (pprint/pprint (cursor-info [text-document (:line position) (:character position)])))
      :info
      nil)))

(defn cursor-info-raw [{:keys [text-document position]}]
  (shared/logging-task
    :cursor-info-raw
    (cursor-info [text-document (:line position) (:character position)])))

(defn clojuredocs-raw [{:keys [sym-name sym-ns]} {:keys [db*]}]
  (shared/logging-task
    :clojuredocs-raw
    (f.clojuredocs/find-docs-for sym-name sym-ns db*)))

(defn ^:private refactor [refactoring [doc-id line character & args] {:keys [db*] :as components}]
  (let [db @db*
        row (inc (int line))
        col (inc (int character))
        ;; TODO Instead of v=0 should I send a change AND a document change
        v (get-in db [:documents doc-id :v] 0)
        loc (some-> (parser/zloc-of-file db doc-id)
                    (parser/to-pos row col))]
    (f.refactor/call-refactor {:refactoring (keyword refactoring)
                               :db          db
                               :loc         loc
                               :uri         doc-id
                               :row         row
                               :col         col
                               :args        args
                               :version     v}
                              components)))

(defn execute-command [{:keys [command arguments]} {:keys [producer] :as components}]
  (cond
    (= command "server-info")
    (server-info-log components)

    (= command "cursor-info")
    (cursor-info-log {:text-document (nth arguments 0)
                      :position {:line (nth arguments 1)
                                 :character (nth arguments 2)}}
                     components)

    (some #(= % command) f.refactor/available-refactors)
    (shared/logging-task
      :execute-command
      ;; TODO move components upper to a common place
      (when-let [{:keys [edit show-document-after-edit]} (refactor command arguments components)]
        ;; waits for client to apply edit before showing doc/moving cursor
        (producer/publish-workspace-edit producer edit)
        (when show-document-after-edit
          (->> (update show-document-after-edit :range #(or (some-> % shared/->range)
                                                            shared/full-file-range))
               (producer/show-document-request producer)))
        edit))))

(defn hover [{:keys [text-document position]} {:keys [db*]}]
  (shared/logging-task
    :hover
    (let [[line column] (shared/position->line-column position)]
      (f.hover/hover text-document line column db*))))

(defn signature-help [{:keys [text-document position _context]}]
  (shared/logging-task
    :signature-help
    (let [[line column] (shared/position->line-column position)]
      (f.signature-help/signature-help text-document line column db/db*))))

(defn formatting [{:keys [text-document]}]
  (shared/logging-task
    :formatting
    (f.format/formatting text-document db/db*)))

(defn range-formatting [{:keys [text-document range]}]
  (process-after-changes
    :range-formatting text-document
    (let [db @db/db*
          start (:start range)
          end (:end range)
          format-pos {:row (inc (:line start))
                      :col (inc (:character start))
                      :end-row (inc (:line end))
                      :end-col (inc (:character end))}]
      (f.format/range-formatting text-document format-pos db))))

(defn dependency-contents [doc-id {:keys [db*]}]
  (shared/logging-task
    :dependency-contents
    (f.java-interop/read-content! doc-id @db*)))

(defn code-actions
  [{:keys [range context text-document]}]
  (process-after-changes
    :code-actions text-document
    (let [db @db/db*
          diagnostics (-> context :diagnostics)
          line (-> range :start :line)
          character (-> range :start :character)
          row (inc line)
          col (inc character)
          root-zloc (parser/safe-zloc-of-file db text-document)
          client-capabilities (get db :client-capabilities)]
      (f.code-actions/all root-zloc text-document row col diagnostics client-capabilities db))))

(defn code-lens
  [{:keys [text-document]}]
  (process-after-changes
    :code-lens text-document
    (f.code-lens/reference-code-lens text-document @db/db*)))

(defn code-lens-resolve
  [{[text-document row col] :data range :range}]
  (shared/logging-task
    :resolve-code-lens
    (f.code-lens/resolve-code-lens text-document row col range @db/db*)))

(defn semantic-tokens-full
  [{:keys [text-document]}]
  (process-after-changes
    :semantic-tokens-full text-document
    (let [data (f.semantic-tokens/full-tokens text-document @db/db*)]
      {:data data})))

(defn semantic-tokens-range
  [{:keys [text-document] {:keys [start end]} :range}]
  (process-after-changes
    :semantic-tokens-range text-document
    (let [db @db/db*
          range {:name-row (inc (:line start))
                 :name-col (inc (:character start))
                 :name-end-row (inc (:line end))
                 :name-end-col (inc (:character end))}
          data (f.semantic-tokens/range-tokens text-document range db)]
      {:data data})))

(defn prepare-call-hierarchy
  [{:keys [text-document position]}]
  (shared/logging-task
    :prepare-call-hierarchy
    (f.call-hierarchy/prepare text-document
                              (inc (:line position))
                              (inc (:character position)) db/db*)))

(defn call-hierarchy-incoming
  [{:keys [item]}]
  (shared/logging-task
    :call-hierarchy-incoming-calls
    (let [uri (:uri item)
          row (inc (-> item :range :start :line))
          col (inc (-> item :range :start :character))]
      (f.call-hierarchy/incoming uri row col db/db*))))

(defn call-hierarchy-outgoing
  [{:keys [item]}]
  (shared/logging-task
    :call-hierarchy-outgoing-calls
    (let [uri (:uri item)
          row (inc (-> item :range :start :line))
          col (inc (-> item :range :start :character))]
      (f.call-hierarchy/outgoing uri row col db/db*))))

(defn linked-editing-ranges
  [{:keys [text-document position]}]
  (shared/logging-task
    :linked-editing-range
    (let [db @db/db*
          row (-> position :line inc)
          col (-> position :character inc)]
      (f.linked-editing-range/ranges text-document row col db))))

(defn will-rename-files [{:keys [files]} {:keys [db*]}]
  (shared/logging-task
    :will-rename-files
    (f.file-management/will-rename-files files @db*)))
