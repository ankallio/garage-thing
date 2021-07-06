# Installation to a Raspberry Pi device

* Install WebThings Gateway
* Enable SSH access in Gateway UI
* Install `gpio` if needed
* Install java with `sudo apt-get install openjdk-8-jre`
* Create directory `/opt/garagething` owned by user `pi`
* Copy JAR file to `/opt/garagething/garagething.jar`
* Copy `service/garagething.service` to directory `/etc/systemd/system`
* Run `sudo systemctl enable garagething.service`
* Run `sudo systemctl start garagething.service`
