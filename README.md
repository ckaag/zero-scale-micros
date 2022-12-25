# ZSM - Zero-Scaled Microservices
Useful when having a ton of Spring Boot Microservices and wanting to run them locally, but not having enough memory at the same time.

Instead, this will take all those microservices as configuration, and spawn a small embedded Javalin proxy on a new localhost port for each service, and act as a eureka server that broadcasts those proxies under the configured service name. If a proxy is called by HTTP, it uses the Testcontainer library to spin up a container for the service to handle the request. Containers that are not being requested for a few minutes (configure with `zsm.containerStandByInSeconds`) will be shutdown until next request.

## Isn't this highly inefficient?
Yes, it is. This is only useful if you cannot keep all your microservices in memory at the same time, or with reasonable work find a subset that fits but still fully works.

If you can run most of your application code on a single developer machine it gives you a much easier way to experiment. This is the only use case for ZSM.
If instead you can shrink your services down to do the same without, please do so. It even saves you money and the planet CO2 emissions if your memory footprint shrinks in production as well.

## Version
This is draft 0.1 and still needs manual production testing before the interface is finalized.


## How to use
Use your application.properties to configure every docker image to use. You may also use local Dockerfile, but this is not recommended as the build might be too slow for reasonable usage.

Do not forget to set your registry and tag if necessary. All additional configuration follows Testcontainers as this is the underlying library.

To simplify setup of shared dependencies / sidecars, you might also define a key `zsm.dockercompose=/path/to/my/docker-compose.yml`. It's useful to reuse existing compose files for stuff like databases and other semi-persistent services. Ports will be used as given by the file.

## Example configuration
```
server:
  port: 8761 # to use the default eureka port
eureka:
  client:
    register-with-eureka: false # recommended
    fetch-registry: false # recommended
zsm:
  overwrites:
    -
      name: "localhostprovider" # assuming you have a service called 'localhostprovider' registering with Eureka, always use the given service and ignore every mock below with that service id / do not keep a proxy for this service (useful if you're debugging / developing the service 'localhostprovider' but want to keep every other microservice running within ZSM)
  docker-compose: .docker/compose/docker-compose.yml # e.g. to set up your database and other persistent dependencies
  services:
    -
      name: "echo" # used as service name in Eureka. It's recommended to go with upper case for most auto config scenarios
      image: "nginx:1.22.1" # just a normal docker container
      internalPort: 80 # should match the internal http port of the container (defaults to 8080) 
    -
      name: "demo"
      dockerfile: ".docker/demo/Dockerfile" # either use `image` or `dockerfile`. The latter can be the path to a Dockerfile or the path to a directory containing one
      internalPort: 8080
      profile: "local" # if you need to use a spring profile or two, do it with this option
      env: # will be sent 1:1 to the container, e.g. `SPRING_APPLICATION_JSON` can be used for customizing
        "ORIGINAL_SPRING_APPLICATION_JSON": "{\"spring\":{\"datasource\":{\"url\":\"jdbc:mysql://localhost/test\"}},\"server\":{\"port\":8080}}"
```

## What do I need outside the containers
Services you spawn yourself (e.g. custom spring boot applications not inside the properties, running under localhost) should set `eureka.instance.hostname=host.testcontainers.internal` otherwise they might use a host name not visible to docker containers. You can add this host as an alias for `127.0.0.1` locally to work with it outside the containers.
