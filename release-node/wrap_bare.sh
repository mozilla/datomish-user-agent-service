#!/bin/sh

set -e

(cat release-node/wrapper.prefix && cat target/release-node/datomish_user_agent_service.bare.js && cat release-node/wrapper.suffix) > target/release-node/datomish_user_agent_service.js

echo "Packed target/release-node/datomish_user_agent_service.js"
