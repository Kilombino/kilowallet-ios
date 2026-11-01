#!/bin/bash
# The lnd engine into iosApp/Frameworks, checked against lnd-engine/ENGINE (see fetch-engine.sh).
exec "$(dirname "$0")/fetch-engine.sh" lnd
