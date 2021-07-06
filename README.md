# garage-thing

A WebThing for a Raspberry Pi based [WebThings](https://webthings.io/) system which operates a garage door with a relay and shows the door's open state with a reed switch sensor, both connected to the Pi's GPIO ports.

The garage-thing is a Java based service running in the background. It is added on to the WebThings Gateway running on the same device (or another device on the same network) and operated through the Gateway.

## Installation on a Raspberry Pi device

* Install WebThings Gateway on the device
* Enable SSH access in Gateway UI
* Install `gpio` if needed
* Install java with `sudo apt-get install openjdk-8-jre`
* Create directory `/opt/garagething` owned by user `pi`
* Copy JAR file to `/opt/garagething/garagething.jar`
* Copy `service/garagething.service` to directory `/etc/systemd/system`
* Run `sudo systemctl enable garagething.service`
* Run `sudo systemctl start garagething.service`

## TODO
* Describe GPIO pin configuration for relay and sensor