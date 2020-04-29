(ns frontend.components.hiccup
  (:refer-clojure :exclude [range])
  (:require [frontend.config :as config]
            [cljs.core.match :refer-macros [match]]
            [clojure.string :as string]
            [frontend.util :as util]
            [rum.core :as rum]
            [frontend.state :as state]
            [frontend.db :as db]
            [dommy.core :as d]
            [goog.dom :as gdom]
            [frontend.expand :as expand]
            [frontend.components.editor :as editor]
            [frontend.components.svg :as svg]
            [frontend.handler :as handler]
            [goog.object :as gobj]))

;; TODO:
;; add `key`

(defn- remove-nils
  [col]
  (remove nil? col))

(defn anchor-link
  [s]
  (.anchorLink js/window.MldocOrg s))

(defn vec-cat
  [& args]
  (->> (apply concat args)
       remove-nils
       vec))

(defn ->elem
  ([elem items]
   (->elem elem nil items))
  ([elem attrs items]
   (let [elem (keyword elem)]
     (if attrs
       (vec
        (cons elem
              (cons attrs
                    (seq items))))
       (vec
        (cons elem
              (seq items)))))))

(defn- join-lines
  [l]
  (string/join "\n" l))

(defn- string-of-url
  [url]
  (match url
    (:or ["File" s] ["Search" s])
    s
    ["Complex" m]
    (let [{:keys [link protocol]} m]
      (if (= protocol "file")
        link
        (str protocol ":" link)))))

;; TODO: safe encoding asciis
;; TODO: image link to another link
(defn image-link [url href label]
  [:img {:src href
         :title label}])

(defn repetition-to-string
  [[[kind] [duration] n]]
  (let [kind (case kind
               "Dotted" "."
               "Plus" "+"
               "DoublePlus" "++")]
    (str kind n (string/lower-case (str (first duration))))))

(defn timestamp-to-string
  [{:keys [active date time repetition wday active]}]
  (let [{:keys [year month day]} date
        {:keys [hour min]} time
        [open close] (if active ["<" ">"] ["[" "]"])
        repetition (if repetition
                     (str " " (repetition-to-string repetition))
                     "")
        hour (if hour (util/zero-pad hour))
        min  (if min (util/zero-pad min))
        time (cond
               (and hour min)
               (util/format " %s:%s" hour min)
               hour
               (util/format " %s" hour)
               :else
               "")]
    (util/format "%s%s-%s-%s %s%s%s%s"
                 open
                 (str year)
                 (util/zero-pad month)
                 (util/zero-pad day)
                 wday
                 time
                 repetition
                 close)))

(defn timestamp [{:keys [active date time repetition wday] :as t} kind]
  (let [prefix (case kind
                 "Scheduled"
                 [:i {:class "fa fa-calendar"
                      :style {:margin-right 3.5}}]
                 "Deadline"
                 [:i {:class "fa fa-calendar-times-o"
                      :style {:margin-right 3.5}}]
                 "Date"
                 nil
                 "Closed"
                 nil
                 "Started"
                 [:i {:class "fa fa-clock-o"
                      :style {:margin-right 3.5}}]
                 "Start"
                 "From: "
                 "Stop"
                 "To: "
                 nil)]
    (let [class (if (= kind "Closed")
                  "line-through")]
      [:span.timestamp (cond-> {:active (str active)}
                         class
                         (assoc :class class))
       prefix
       (timestamp-to-string t)])))

(defn range [{:keys [start stop]} stopped?]
  [:div {:class "timestamp-range"
         :stopped stopped?}
   (timestamp start "Start")
   (timestamp stop "Stop")])

(declare map-inline)
(defn inline
  [item]
  (match item
    ["Plain" s]
    s
    ["Spaces" s]
    s
    ["Superscript" l]
    (->elem :sup (map-inline l))
    ["Subscript" l]
    (->elem :sub (map-inline l))
    ["Emphasis" [[kind] data] ]
    (let [elem (case kind
                 "Bold" :b
                 "Italic" :i
                 "Underline" :u
                 "Strike_through" :del)]
      (->elem elem (map-inline data)))
    ["Entity" e]
    [:span {:dangerouslySetInnerHTML
            {:__html (:html e)}}]

    ["Latex_Fragment" ["Displayed" s]]
    (util/format "\\[%s\\]" s)

    ["Latex_Fragment" ["Inline" s]]
    (util/format "\\(%s\\)" s)

    ["Target" s]
    [:a {:href (str "/page/" (util/url-encode s))} (str "<<" s ">>")]

    ["Radio_Target" s]
    [:a {:href (str "/page/" (util/url-encode s))} (str "<<<" s ">>>")]

    ["Link" link]
    (let [{:keys [url label]} link]
      (let [href (string-of-url url)
            img-formats (set (map name config/img-formats))]
        (if (some (fn [fmt] (re-find (re-pattern (str "\\." fmt)) href)) img-formats)
          (image-link url href label)
          (let [[href label] (match url
                               ["Search" s]
                               [(str "#" (anchor-link s))
                                s]
                               :else
                               [href
                                (map-inline label)])]
            (->elem
             :a
             {:href href}
             label)))))

    ["Verbatim" s]
    [:code s]

    ["Code" s]
    [:code s]

    ["Inline_Source_Block" x]
    [:code (:code x)]

    ["Export_Snippet" "html" s]
    [:span {:dangerouslySetInnerHTML
            {:__html s}}]

    ["Break_Line"]
    [:br]
    ["Hard_Break_Line"]
    [:br]

    ["Timestamp" ["Scheduled" t]]
    (timestamp t "Scheduled")
    ["Timestamp" ["Deadline" t]]
    (timestamp t "Deadline")
    ["Timestamp" ["Date" t]]
    (timestamp t "Date")
    ["Timestamp" ["Closed" t]]
    (timestamp t "Closed")
    ["Timestamp" ["Range" t]]
    (range t false)
    ["Timestamp" ["Clock" ["Stopped" t]]]
    (range t true)
    ["Timestamp" ["Clock" ["Started" t]]]
    (timestamp t "Started")

    ["Cookie" ["Percent" n]]
    [:span {:class "cookie-percent"}
     (util/format "[d%%]" n)]

    ["Cookie" ["Absolute" current total]]
    [:span {:class "cookie-absolute"}
     (util/format "[%d/%d]" current total)]

    ["Footnote_Reference" options]
    (let [{:keys [id name]} options
          encode-name (util/url-encode name)]
      [:sup
       [:a {:id (str "fnr." encode-name)
            :class "footref"
            :href (str "#fn." encode-name)}
        name]])

    ;; TODO:
    ["Macro" options]
    (let [{:keys [name arguments]} options]
      "")

    :else
    ""))

(declare block)
(declare blocks)

(rum/defc heading-child
  [block]
  block)

(defn- has-children?
  [heading-id level]
  (or
   ;; non heading children
   (when-let [node (gdom/getElement heading-id)]
     (> (count (expand/get-heading-body node))
        1))

   ;; other headings children
   (when-let [next-heading (gobj/get (gdom/getElement heading-id)
                                     "nextSibling")]
     (when-let [child-level (d/attr next-heading "level")]
       (> child-level level)))))

(rum/defcs heading-cp < rum/reactive
  (rum/local false ::control-show?)
  (rum/local false ::edit?)
  (rum/local false ::collapsed?)
  [state {:heading/keys [uuid level children meta content dummy? lock? show-page? page] :as heading} heading-part config]
  (let [edit? (get state ::edit?)
        control-show? (get state ::control-show?)
        heading-id (str "ls-heading-parent-" uuid)
        collapsed-atom? (get state ::collapsed?)
        toggle-collapsed? (state/sub [:ui/collapsed-headings heading-id])
        collapsed? (or toggle-collapsed? @collapsed-atom?)]
    (let [edit-input-id (str "edit-box-" uuid)]
      (when-not lock?
        [:div.ls-heading-parent.flex-1 {:key (str uuid)
                                        :id heading-id
                                        :level level}
         (if show-page?
           (let [page (db/entity (:db/id page))]
             [:h2
              [:a {:href (str "/page/" (util/url-encode (:page/name page)))}
               (:page/name page)]]))

         ;; control
         [:div.flex.flex-row
          {:style {:cursor "pointer"}
           :on-mouse-over (fn []
                            (when (has-children? heading-id level)
                              (reset! control-show? true)))
           :on-mouse-out (fn []
                           (when (has-children? heading-id level)
                             (reset! control-show? false)))}
          [:a.heading-control.pt-1
           {:id (str "control-" uuid)
            :class "block no-underline text-gray-700 hover:text-gray-700 transition ease-in-out duration-150 mr-1"
            :on-click (fn []
                        (let [id (str "ls-heading-parent-" uuid)]
                          (if collapsed?
                            (expand/expand! (:id config) id)
                            (expand/collapse! (:id config) id))
                          (reset! collapsed-atom? (not collapsed?))))}
           (cond
             collapsed?
             (svg/caret-right)

             (and @control-show?
                  (has-children? heading-id level))
             (svg/caret-down)

             :else
             nil)]

          (if @edit?
            (editor/box content {:on-hide (fn [value]
                                            (reset! edit? false)
                                            (let [current-input (:edit-input-id @state/state)]
                                              (when (or (nil? current-input)
                                                        (= current-input edit-input-id))
                                                (swap! state/state assoc
                                                       :edit? false
                                                       :edit-input-id nil)))
                                            (handler/save-heading-if-changed! heading value))
                                 :dummy? dummy?}
                        edit-input-id)
            [:div.flex-1.heading-body
             {:on-click (fn [e]
                          (when-not (util/link? (gobj/get e "target"))
                            (reset! edit? true)
                            (handler/reset-cursor-range! (gdom/getElement heading-id))
                            (swap! state/state assoc
                                   :edit? true
                                   :edit-input-id edit-input-id)))}
             heading-part

             ;; non-heading children
             (when (seq children)
               (for [child children]
                 (let [block (block config child)]
                   (rum/with-key (heading-child block)
                     (cljs.core/random-uuid)))))])]]))))

(defn heading
  [config {:heading/keys [uuid title tags marker level priority anchor meta numbering children]
           :as t}]
  (let [marker (if marker
                 [:span {:class (str "task-status " (string/lower-case marker))
                         :style {:margin-right 3.5}}
                  (string/upper-case marker)])
        priority (if priority
                   [:span {:class "priority"
                           :style {:margin-right 3.5}}
                    (util/format "[#%s]" (str priority))])
        tags (when-not (empty? tags)
               (->elem
                :span
                {:class "heading-tags"}
                (mapv (fn [{:keys [db/id tag/name]}]
                        [:span.tag {:key (str "tag-" id)}
                         [:span {:class name}
                          name]])
                      tags)))
        element (keyword (str "h" level))
        level-str [:a.control-level {:href (str "/page/" uuid)}
                   (str (apply str (repeat level "*")) " ")]
        heading-part (->elem element
                             {:id anchor
                              :uuid (str uuid)}
                             (remove-nils
                              (concat
                               [level-str
                                marker
                                priority]
                               (map-inline title)
                               [tags])))]
    (heading-cp t heading-part config)))

(defn list-element
  [l]
  (match l
    [l1 & tl]
    (let [{:keys [ordered name]} l1]
      (cond
        name
        :dl
        ordered
        :ol
        :else
        :ul))

    :else
    :ul))

(defn list-item
  [config {:keys [name content checkbox items number] :as l}]
  (let [content (when-not (empty? content)
                  (match content
                    [["Paragraph" i] & rest]
                    (vec-cat
                     (map-inline i)
                     (blocks config rest))
                    :else
                    (blocks config content)))
        checked? (some? checkbox)
        items (if (seq items)
                (->elem
                 (list-element items)
                 (for [item items]
                   (list-item config item))))]
    (cond
      name
      [:dl {:checked checked?}
       [:dt name]
       (->elem :dd
               (vec-cat content [items]))]

      :else
      (->elem
       :li
       {:checked checked?}
       (vec-cat
        [(->elem
          :p
          content)]
        [items])))))

(defn table
  [{:keys [header groups col_groups]}]
  (let [tr (fn [elm cols]
             (->elem
              :tr
              (mapv (fn [col]
                      (->elem
                       elm
                       {:scope "col"
                        :class "org-left"}
                       (map-inline col)))
                    cols)))
        tb-col-groups (try
                        (mapv (fn [number]
                                (let [col-elem [:col {:class "org-left"}]]
                                  (->elem
                                   :colgroup
                                   (repeat number col-elem))))
                              col_groups)
                        (catch js/Error e
                          []))
        head (if header
               [:thead (tr :th header)])
        groups (mapv (fn [group]
                       (->elem
                        :tbody
                        (mapv #(tr :td %) group)))
                     groups)]
    (->elem
     :table
     {:border 2
      :cell-spacing 0
      :cell-padding 6
      :rules "groups"
      :frame "hsides"}
     (vec-cat
      tb-col-groups
      (cons head groups)))))

(defn map-inline
  [col]
  (map inline col))

(defn block
  [config item]
  (try
    (match item
      ["Paragraph" l]
      (->elem :p (map-inline l))
      ["Horizontal_Rule"]
      [:hr]
      ["Heading" h]
      (heading config h)
      ["List" l]
      (->elem
       (list-element l)
       (map #(list-item config %) l))
      ["Table" t]
      (table t)
      ["Math" s]
      [:div.mathblock
       (str "$$" s "$$")]
      ["Example" l]
      [:pre
       (join-lines l)]
      ["Src" options]
      (let [{:keys [language options lines]} options
            attr (if language
                   {:data-lang language
                    :class lines})]
        [:pre
         [:code attr
          (join-lines lines)]])
      ["Quote" l]
      (->elem
       :blockquote
       (blocks config l))
      ["Export" "html" options content]
      [:div {:dangerouslySetInnerHTML
             {:__html content}}]
      ["Custom" name options l]
      (->elem
       :div
       {:class name}
       (blocks config l))
      ["Latex_Fragment" l]
      [:p.latex-fragment
       (inline ["Latex_Fragment" l])]
      ["Latex_Environment" name option content]
      (let [content (util/format "\n\begin{%s} {%s}\n%s\n\\end{%s}"
                                 name
                                 option
                                 (join-lines content)
                                 name)]
        [:div.latex-environment
         content])
      ["Footnote_Definition" name definition]
      (let [id (util/url-encode name)]
        [:div.footdef
         [:div.footpara
          (block config ["Paragraph" definition])]
         [:sup
          [:a {:id (str "fn." id)
               :class "footnum"
               :href (str "#fnr." id)}
           (str name "↩︎")]]])

      :else
      "")
    (catch js/Error e
      (prn "Convert to html failed, error: " e)
      "")))

(defn blocks
  [config col]
  (map #(block config %) col))

;; TODO: handle case of no headings
(defn ->hiccup
  [headings config]
  (let [headings (mapv (fn [heading] ["Heading" heading]) headings)]
    (->elem
     :div.content
     (blocks config headings))))

(comment
  ;; timestamps
  ;; [2020-02-10 Mon 13:22]
  ;; repetition
  (def r1 "<2005-10-01 Sat +1m>")
  ;; TODO: mldoc_org add supports
  (def r2 "<2005-10-01 Sat +1m -3d>")

  (def l
    "1. First item
hello world
2. Second item
nice
3. Third item")

  (def t
    "| Name  | Phone | Age |
|-------+-------+-----|
| Peter |  1234 |  17 |
| Anna  |  4321 |  25 |")
  )