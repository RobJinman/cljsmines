(ns minesweeper.core
  (:require [goog.dom :as gdom]
            [goog.string :as gstring]
            [goog.string.format]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [clojure.set]))

(enable-console-print!)

(def levels {"beginner" {:size [8 8] :mines 10}
             "intermediate" {:size [16 16] :mines 40}
             "expert" {:size [16 30] :mines 99}})

(defn empty-row [cols]
  (vec (repeat cols 0)))

;; A number between 0 and 8 corresponds to the number of surrounding mines.
;; A value of :X represents a mine.
(defn empty-grid [[rows cols]]
  (vec (repeat rows (empty-row cols))))

;; 1 = revealed cell, 0 = hidden cell
(defn empty-mask [[rows cols]]
  (vec (repeat rows (empty-row cols))))

;; 1 = flag, 0 = no flag
(defn empty-flags [[rows cols]]
  (vec (repeat rows (empty-row cols))))

(defn set-high-score!
  [level score]
  (.setItem js/localStorage level score))

(defn get-high-score
  [level]
  (let [score (.getItem js/localStorage level)]
    (if score
      (js/parseInt score)
      nil)))

(defn dbg-clear-high-score!
  [level]
  (.removeItem js/localStorage level))

(defn populate-grid
  "Takes an empty grid and populates it randomly with mines."
  [grid [rows cols] num-mines]
  (loop [i 0
         grid grid]
    (let [row (rand rows)
          col (rand cols)
          is-mine (= :X (get-in grid [row col]))]
      (if (< i num-mines)
        (recur (if is-mine i (inc i))
               (if is-mine grid (assoc-in grid [row col] :X)))
        grid))))

(defn neighbour-coords
  [[rows cols] [row col]]
  (let [indices [[-1 -1] [0 -1] [1 -1] [-1 0] [1 0] [-1 1] [0 1] [1 1]]]
    (reduce (fn [coords [i j]]
              (let [x (+ row i)
                    y (+ col j)]
                (if (and (< -1 x rows) (< -1 y cols))
                  (conj coords [x y])
                  coords)))
            []
            indices)))

(defn count-nearby-flags
  "Returns the number of flags surrounding the cell."
  [[rows cols] flags [row col]]
  (reduce (fn [count cell]
            (let [flag (get-in flags cell)]
              (+ count flag)))
          0
          (neighbour-coords [rows cols] [row col])))

(def count-nearby-exposed count-nearby-flags)

(defn count-nearby-hidden
  "Returns the number of hidden cells surrounding the cell."
  [[rows cols] mask [row col]]
  (let [num-neighbours (count (neighbour-coords [rows cols] [row col]))
        num-exposed (count-nearby-exposed [rows cols] mask [row col])]
    (- num-neighbours num-exposed)))

(defn can-spread-sweep?
  "If the cell is surrounded by precisely the correct number of flags in addition
  to at least 1 unflagged cell, the player can perform a spread-sweep by clicking
  this cell."
  [[rows cols] grid mask flags [row col]]
  (let [value (get-in grid [row col])
        num-flags (count-nearby-flags [rows cols] flags [row col])
        num-hidden (count-nearby-hidden [rows cols] mask [row col])]
    (and (> value 0)
         (= value num-flags)
         (< num-flags num-hidden))))

(defn flatten-grid
  "Returns a flat vector of [row col value] vectors to enable easy iteration over
  the grid."
  [grid]
  (for [[i row] (map-indexed vector grid)
        [j val] (map-indexed vector row)]
    [i j val]))

(defn count-flags
  "This actually sums all the numbers in whatever 2D array it is given. When given
  the flags array, it will return the total number of flags."
  [flags]
  (reduce (fn [sum [_ _ n]]
            (+ sum n))
          0
          (flatten-grid flags)))

(defn label-grid
  "Takes a grid populated with mines and adds in the numbers."
  [[rows cols] grid]
  (reduce (fn [labelled [i j val]]
            (if (= :X val)
              (let [neighbours (neighbour-coords [rows cols] [i j])]
                (reduce (fn [labelled [ni nj]]
                          (update-in labelled [ni nj]
                                     #(if (number? %) (inc %) %)))
                        labelled
                        neighbours))
              labelled))
          grid
          (flatten-grid grid)))

(defn get-time
  "Seconds since the epoch."
  []
  (let [millis (.getTime (js/Date.))]
    (/ millis 1000)))

(defn calc-elapsed
  [time-started]
  (int (- (get-time) time-started)))

(defn zero-region
  "Returns a list of cell coords belonging to the zero region surrounding the cell. The
  cell is expected to have a value of 0 in grid."
  [[rows cols] grid [row col] region]
  (let [neighbours (neighbour-coords [rows cols] [row col])
        region (conj region [row col])]
    (if (= 0 (get-in grid [row col]))
      (reduce (fn [region [nx ny]]
                (if (not (contains? region [nx ny]))
                  (clojure.set/union region (zero-region [rows cols] grid [nx ny] region))
                  region))
              region
              neighbours)
      region)))

(defn update-mask
  "Sets the mask to 1 (corresponding to exposed) at each cell."
  [mask cells]
  (reduce (fn [mask [r c]]
            (assoc-in mask [r c] 1))
          mask
          cells))

(defn values-at
  "List of values at the locations given by cells."
  [grid cells]
  (map #(get-in grid %) cells))

(defn calc-game-state
  "Returns one of :alive, :dead, or :victorious."
  [grid mask num-mines swept-cells]
  (let [num-hidden 
        (reduce (fn [total [r c n]]
                  (if (= 0 n)
                    (inc total)
                    total))
                0
                (flatten-grid mask))
        values (values-at grid swept-cells)
        dead (contains? (set values) :X)]
    (if dead
      :dead
      (if (= num-mines num-hidden)
        :victorious
        :alive))))

(defn sweep-cell
  "Returns a new mask."
  [[rows cols] grid mask [row col]]
  (let [sweep-region (if (= 0 (get-in grid [row col]))
                       (zero-region [rows cols] grid [row col] #{})
                       [[row col]])]
    (update-mask mask sweep-region)))

(defn sweep-cells
  "Returns a new mask."
  [[rows cols] grid mask cells]
  (reduce (fn [mask cell]
            (sweep-cell [rows cols] grid mask cell))
          mask
          cells))

(defn flag-remaining
  "Flag any unflagged mines."
  [flags grid]
  (reduce (fn [flags [r c val]]
            (if (= :X val)
              (assoc-in flags [r c] 1)
              flags))
          flags
          (flatten-grid grid)))

(defn new-state
  "Construct a fresh game state for the given difficulty level."
  [level]
  (let [[rows cols] (get-in levels [level :size])
        num-mines (get-in levels [level :mines])]
    {:controls {:level level
                :react-key "controls"}
     :level (get levels level)
     :grid (label-grid [rows cols] (populate-grid (empty-grid [rows cols]) [rows cols] num-mines))
     :mask (empty-mask [rows cols])
     :flags (empty-flags [rows cols])
     :time-started (get-time)
     :tick-count 0
     ;; Valid values are :pending :alive, :dead, and :victorious
     :game-state :pending
     :high-score (get-high-score level)}))

(defn update-high-score!
  "If the time elapsed repesents a new high-score, persist it to local
  storage."
  [state]
  (let [level (get-in @state [:controls :level])
        time-started (get @state :time-started)
        elapsed (calc-elapsed time-started)
        high-score (get-high-score level)]
    (if (or (= nil high-score) (< elapsed high-score))
      (do (swap! state assoc :high-score elapsed)
          (set-high-score! level elapsed)))))

(defn sweep-cell!
  "Perform the sweep, and update the app state."
  [state [row col]]
  (let [[rows cols] (get-in @state [:level :size])
        grid (get @state :grid)
        mask (get @state :mask)
        flags (get @state :flags)
        num-mines (get-in @state [:level :mines])
        new-mask (sweep-cell [rows cols] grid mask [row col])
        game-state (calc-game-state grid new-mask num-mines [[row col]])]
    (swap! state assoc :game-state game-state)
    (swap! state assoc :mask new-mask)
    (if (= :victorious game-state)
      (do (swap! state assoc :flags (flag-remaining flags grid))
          (update-high-score! state)))))

(defn sweep-spread!
  "Perform a spread-sweep and update the app state."
  [state [row col]]
  (let [[rows cols] (get-in @state [:level :size])
        grid (get @state :grid)
        mask (get @state :mask)
        flags (get @state :flags)
        num-mines (get-in @state [:level :mines])]
    (if (can-spread-sweep? [rows cols] grid mask flags [row col])
      (let [swept (reduce (fn [swept cell]
                            (if (= 0 (get-in flags cell))
                              (conj swept cell)
                              swept))
                          []
                          (neighbour-coords [rows cols] [row col]))
            new-mask (sweep-cells [rows cols] grid mask swept)
            game-state (calc-game-state grid new-mask num-mines swept)]
        (swap! state assoc :mask new-mask)
        (swap! state assoc :game-state game-state)
        (if (= :victorious game-state)
          (do (swap! state assoc :flags (flag-remaining flags grid))
              (update-high-score! state)))))))

(defn flag-cell!
  [state [row col]]
  (swap! state assoc-in [:flags row col] 1))

(defn unflag-cell!
  [state [row col]]
  (swap! state assoc-in [:flags row col] 0))

(defn reset-game!
  [state]
  (let [level (get-in @state [:controls :level])]
    (reset! state (new-state level))))

(defn start-game!
  "Transition the game state from :pending to :alive."
  [state]
  (do (swap! state assoc :game-state :alive)
      (swap! state assoc :time-started (get-time))))

(defn tick!
  "Any components that need regular refreshing can depend on :tick-count,
  which is incremented periodically."
  [state]
  (swap! state update :tick-count inc))

(defmulti read (fn [env key params] key))

(defmethod read :default
  [{:keys [state query] :as env} key params]
  (let [st @state]
    (if-let [[_ v] (find st key)]
      {:value v}
      {:value :not-found})))

(defn if-alive
  "Call the function if the game-state is :alive."
  [state fn & args]
  (if (= :alive (get @state :game-state))
    (apply fn args)))

(defn alive-or-pending?
  [state]
  (contains? #{:pending :alive} (get @state :game-state)))

(defn start-game-if-pending!
  [state]
  (if (= :pending (get @state :game-state))
    (start-game! state)))

(defmulti mutate (fn [env key params] key))

(defmethod mutate `tick
  [{:keys [state]} key params]
  {:action #(if-alive state tick! state)})

(defmethod mutate `sweep
  [{:keys [state]} key {:keys [row col]}]
  {:action #(if (alive-or-pending? state)
              (do (start-game-if-pending! state)
                  (sweep-cell! state [row col])))})

(defmethod mutate `reset
  [{:keys [state]} key params]
  {:action #(reset-game! state)})

(defmethod mutate `spread-sweep
  [{:keys [state]} key {:keys [row col]}]
  {:action #(if-alive state sweep-spread! state [row col])})

(defmethod mutate `flag
  [{:keys [state]} key {:keys [row col]}]
  {:action #(if (alive-or-pending? state)
              (do (start-game-if-pending! state)
                  (flag-cell! state [row col])))})

(defmethod mutate `unflag
  [{:keys [state]} key {:keys [row col]}]
  {:action #(if-alive state unflag-cell! state [row col])})

(defmethod mutate `level-select
  [{:keys [state]} key {:keys [level]}]
  {:action #(swap! state assoc-in [:controls :level] level)})

(def parser (om/parser {:read read :mutate mutate}))

(def app-state
  (atom (new-state "intermediate")))

(def reconciler
  (om/reconciler
   {:state app-state
    :parser parser}))

(defui ExposedCellView
  static om/IQuery
  (query [this]
         [])
  Object
  (render [this]
          (let [{:keys [row col value can-spread]} (om/get-computed this)
                str-val (case value
                          1 "one"
                          2 "two"
                          3 "three"
                          4 "four"
                          5 "five"
                          6 "six"
                          7 "seven"
                          8 "eight"
                          :X "mine"
                          "")]
            (println (str "Rendering exposed cell (" row "," col "," value ")"))            
            (dom/div #js {:className (str "cell revealed "
                                          str-val
                                          (if can-spread " spread" ""))
                          :onClick (fn [e] (om/transact! this `[(spread-sweep {:row ~row :col ~col}) :mask :game-state]))}
                     (dom/span nil
                               (case value
                                 :X ""
                                 0 ""
                                 value))))))

(def exposed-cell-view (om/factory ExposedCellView))

(defui HiddenCellView
  static om/IQuery
  (query [this]
         [])
  Object
  (render [this]
          (let [{:keys [game-state]} (om/props this)
                {:keys [row col value flag]} (om/get-computed this)
                status-str (if (= :dead game-state)
                             (if (= :X value) "mine" "safe")
                             "")]
            (println (str "Rendering hidden cell (" row "," col "," flag ")"))
            (if (= 1 flag)
              (dom/div
               #js {:className (str "cell hidden flagged " status-str)
                    :onContextMenu (fn [e] (do (.preventDefault e)
                                               (om/transact! this `[(unflag {:row ~row :col ~col}) :flags])))})
              (dom/div
               #js {:className (str "cell hidden " status-str)
                    :onClick (fn [e] (om/transact! this `[(sweep {:row ~row :col ~col}) :mask :game-state]))
                    :onContextMenu (fn [e] (do (.preventDefault e)
                                               (om/transact! this `[(flag {:row ~row :col ~col}) :flags])))})))))

(def hidden-cell-view (om/factory HiddenCellView))

(defui CellView
  static om/IQuery
  (query [this]
         [:game-state])
  Object
  (render [this]
          (let [{:keys [game-state]} (om/props this)
                {:keys [row col flag value mask-val can-spread]} (om/get-computed this)]
            (if (= mask-val 1)
              (exposed-cell-view (om/computed {}
                                              {:value value :row row :col col :can-spread can-spread}))
              (hidden-cell-view (om/computed {:game-state game-state}
                                             {:flag flag :value value :row row :col col}))))))

(def cell-view (om/factory CellView))

(defui GridView
  static om/IQuery
  (query [this]
         [])
  Object
  (render [this]
          (println "Rendering grid view")
          (let [{:keys [game-state game-size grid mask flags] :as props} (om/props this)
                key #(+ (* %1 7) %2)] ;; Generate unique key from row and col
            (apply dom/div
                   #js {:className "minesweeper"}
                   (map-indexed (fn [r row] (apply dom/div
                                   #js {:className "row"}
                                   (map-indexed (fn [c val]
                                                  (cell-view (om/computed {:react-key (key r c)
                                                                           :game-state game-state}
                                                                          {:flag (get-in flags [r c])
                                                                           :value val
                                                                           :mask-val (get-in mask [r c])
                                                                           :row r
                                                                           :col c
                                                                           :can-spread (can-spread-sweep? game-size
                                                                                                          grid
                                                                                                          mask
                                                                                                          flags
                                                                                                          [r c])})))
                                                row)))
                                grid)))))

(def grid-view (om/factory GridView))

(defui ControlsView
  static om/IQuery
  (query [this]
         [:level])
  Object
  (render [this]
          (println "Rendering controls view")
          (let [{:keys [level]} (om/props this)]
            (dom/div #js {:className "panel"}
                     (dom/select #js {:className "control"
                                      :value level
                                      :onChange (fn [e]
                                                  (let [value (-> e .-target .-value)]
                                                    (om/transact! this `[(level-select {:level ~value})])))}
                                 (dom/option #js {:value "beginner"} "Beginner")
                                 (dom/option #js {:value "intermediate"} "Intermediate")
                                 (dom/option #js {:value "expert"} "Expert"))
                     (dom/button
                      #js {:className "control"
                           :onClick (fn [e] (om/transact! this `[(reset) :level]))}
                      "Reset")))))

(def controls-view (om/factory ControlsView))

(defui TimerView
  static om/IQuery
  (query [this]
         [])
  Object
  (render [this]
          (println "Rendering timer view")
          (let [{:keys [elapsed]} (om/get-computed this)]
            (dom/div #js {:className "timer"}
                     "Time "
                     (dom/span nil (gstring/format "%03d" elapsed))))))

(def timer-view (om/factory TimerView))

(defui InfoView
  static om/IQuery
  (query [this]
         [:high-score :flags])
  Object
  (render [this]
          (println "Rendering info view")
          (let [{:keys [high-score]} (om/props this)
                {:keys [time-started tick-count num-remaining]} (om/get-computed this)]
            (dom/div #js {:className "info"}
                     (timer-view (om/computed {} {:elapsed (calc-elapsed time-started)}))
                     (dom/div #js {:className "high-score"}
                              "Best "
                              (dom/span nil
                                        (if (nil? high-score) "---" (gstring/format "%03d" high-score))))
                     (dom/div #js {:className "remaining"}
                              "Mines "
                              (dom/span nil
                                        (gstring/format "%03d" num-remaining)))))))

(def info-view (om/factory InfoView))

(defui MainView
  static om/IQuery
  (query [this]
         [:level :grid :mask :flags :game-state :time-started :tick-count :high-score
          {:controls (om/get-query ControlsView)}])
  Object
  (componentDidMount [this]
                     (println "MainView mounted")
                     (js/setInterval #(om/transact! this `[(tick) :tick-count]) 1000))
  (render [this]
          (println "Rendering main view")
          (let [{:keys [controls level grid mask flags game-state time-started tick-count high-score]} (om/props this)
                game-size (get level :size)
                num-mines (get level :mines)
                num-remaining (- num-mines (count-flags flags))]
            (dom/div #js {:className (str "minesweeper-wrap "
                                          (case game-state
                                            :victorious "st-victorious"
                                            :dead "st-dead"
                                            ""))
                          :onContextMenu (fn [e] (.preventDefault e))}
                     (info-view (om/computed {:react-key "info"
                                              :high-score high-score}
                                             {:time-started time-started
                                              :tick-count tick-count
                                              :num-remaining num-remaining}))
                     (grid-view {:game-state game-state
                                 :game-size game-size
                                 :grid grid
                                 :mask mask
                                 :flags flags})
                     (controls-view controls)))))

(om/add-root! reconciler
              MainView (gdom/getElement "app"))
