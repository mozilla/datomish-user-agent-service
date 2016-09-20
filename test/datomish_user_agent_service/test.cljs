;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns datomish-user-agent-service.test
  (:require
   [doo.runner :refer-macros [doo-tests doo-all-tests]]
   [cljs.test :as t :refer-macros [is are deftest testing]]
   datomish-user-agent-service.core-test
   ))

(doo-tests
  'datomish-user-agent-service.core-test
  )
