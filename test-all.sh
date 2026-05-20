#!/bin/sh
set -eu

"$(dirname "$0")/mvnw" test
(cd "$(dirname "$0")/Webserver" && composer test)
