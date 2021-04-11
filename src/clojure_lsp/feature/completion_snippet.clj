(ns clojure-lsp.feature.completion-snippet)

(defn known-snippets [settings]
  [{:label "comment$"
    :detail "Create comment block"
    :insert-text "(comment\n  $0\n  )"}
   {:label "condp$"
    :detail "Create condp"
    :insert-text "(condp ${1:pred} ${2:expr}\n $0)"}
   {:label "def$"
    :insert-text "(def ${1:name} $0)"
    :detail "Create def"}
   {:label "defmethod$"
    :detail "Create defmethod"
    :insert-text "(defmethod ${1:name} ${2:match}\n [${3:args}]\n $0)"}
   {:label "defmulti$"
    :detail "Create defmulti"
    :insert-text "(defmulti ${1:name} ${2:dispatch-fn})"}
   {:label "defn-$"
    :detail "Create private function"
    :insert-text (format "(defn%s ${1:name} [$2]\n  $0)"
                         (if (:use-metadata-for-privacy? settings)
                           " ^:private"
                           "-"))}
   {:label "defn$"
    :insert-text "(defn ${1:foo} [$2]\n  $0)"
    :detail "Create public function"}
   {:label "defprotocol$"
    :detail "Create defprotocol"
    :insert-text "(defprotocol ${1:Name}\n $0)"}
   {:label "defrecord$"
    :detail "Create defrecord"
    :insert-text "(defrecord ${1:Name} [${2:fields}]\n ${3:Protocol}\n $0)"}
   {:label "deftype$"
    :detail "Create deftype"
    :insert-text "(deftype ${1:Name} [${2:fields}]\n ${3:Protocol}\n $0)"}
   {:label "fn$"
    :detail "Create fn"
    :insert-text "(fn [${1:arg-list}] $0)"}
   {:label "if$"
    :detail "Create if"
    :insert-text "(if ${1:test-expr}\n ${2:then-expr}\n ${3:else-expr})"}
   {:label "import$"
    :detail "Create import"
    :insert-text "(:import [${1:package}])"}
   {:label "kwargs$"
    :detail "Create keyword args"
    :insert-text "& {:keys [${1:keys}] :or {${2:defaults}}}"}
   {:label "let$"
    :detail "Create let"
    :insert-text "(let [$0])"}
   {:label "letfn$"
    :detail "Create letfn"
    :insert-text "(letfn [(${1:name} [${2:args}]\n $0)])"}
   {:label "ns$"
    :detail "Create ns"
    :insert-text "(ns $1\n  $0)"}
   {:label "require$"
    :detail "Create require"
    :insert-text "(:require [${1:namespace} :as [$0]])"}
   {:label "use$"
    :detail "Create use"
    :insert-text "(:use [${1:namespace} :only [$0]])"}])
