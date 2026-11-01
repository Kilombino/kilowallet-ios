#!/bin/bash
# The ark engine into iosApp/Frameworks, checked against ark-engine/ENGINE (see fetch-engine.sh).
exec "$(dirname "$0")/fetch-engine.sh" ark
