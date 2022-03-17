# Fin TCP Proxy
Fan in TCP Proxy

## Features
Coalesce multiple tcp connections and forward them to another using only a few connections [2 * num of Cpus].

## Config

Set env var for the following configs if need to customize

1. `forward_port` - port to forward tcp packets to - default 5432 [postgres]
2. `forward_host` - host to forward tcp packets to - default localhost
3. `listen_port` - port to run the tcp proxy on - default 9090

Prometheus metrics exposed on port 9091