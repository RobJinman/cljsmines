CLJS Mines
==========

To run locally

        rlwrap lein run -m clojure.main script/figwheel.clj

Then navigate to http://localhost:3449 in the browser.

Build
-----

Figwheel will build the ClojureScript when the source changes. To build the css, run

        lein less once

or

        lein less auto

