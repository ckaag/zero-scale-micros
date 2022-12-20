# ZSM - Zero-Scaled Microservices


## What do I need
Services you spawn yourself (e.g. custom spring boot applications not inside the properties, running under localhost) should set `eureka.instance.hostname=host.testcontainers.internal` otherwise they might use a host name not visible to docker containers. You can add this host as an alias for `127.0.0.1` locally to work with it outside the containers.
