# SetAndroidProxy
This tool will let you set Android WiFi proxy from Termux

## Setup
1. `cd /sdcard/Download`
2. `curl -LO https://github.com/Real96/SetAndroidProxy/releases/latest/download/setproxy.dex`
3. `curl -LO https://github.com/Real96/SetAndroidProxy/releases/latest/download/setproxy.sh`
4. `su -c 'mv /sdcard/Download/setproxy.dex /sdcard/Download/setproxy.sh /data/local/tmp/'`
5. `su -c 'chmod 644 /data/local/tmp/setproxy.dex && chmod 755 /data/local/tmp/setproxy.sh'`

## Usage
* Check your current WiFi setup: `su -c 'sh /data/local/tmp/setproxy.sh status'`
* Set the manual proxy: `su -c 'sh /data/local/tmp/setproxy.sh <ip> <port> [*google.com,*reddit.com]'`
* Set the auto-config proxy: `su -c 'sh /data/local/tmp/setproxy.sh pac http://<http_server_ip>/proxy.pac'`
* Remove the proxy: `su -c 'sh /data/local/tmp/setproxy.sh off'`
