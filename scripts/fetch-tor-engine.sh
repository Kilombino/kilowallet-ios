#!/bin/bash
# The tor engine into iosApp/Frameworks, checked against tor-engine/ENGINE (see fetch-engine.sh).
exec "$(dirname "$0")/fetch-engine.sh" tor
