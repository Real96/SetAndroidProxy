#!/system/bin/sh
# Sets the proxy of the connected WiFi network. Must be run as root.
# Usage: setproxy.sh HOST PORT [comma,separated,exclusions]
#        setproxy.sh pac URL
#        setproxy.sh off
#        setproxy.sh status
export CLASSPATH=/data/local/tmp/setproxy.dex
exec app_process /system/bin SetProxy "$@"