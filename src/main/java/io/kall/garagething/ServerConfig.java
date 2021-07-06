package io.kall.garagething;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.platform.Platform;
import com.pi4j.platform.PlatformAlreadyAssignedException;
import com.pi4j.platform.PlatformManager;

import io.webthings.webthing.Thing;
import io.webthings.webthing.WebThingServer;

@Configuration
public class ServerConfig {
	
	private static final Logger logger = LoggerFactory.getLogger(ServerConfig.class);
	
	@Value(value = "${garagething.doorsensor.wiringpi.address:0}")
	private int doorSensorWiringPiAddress;
	
	@Value("${garagething.relay.wiringpi.address:2}")
	private int relayWiringPiAddress;
	
	@Value("${garagething.pi4j.platform:}") // "SIMULATED" or "RASPBERRYPI"
	private Platform platform;
	
	@Value("${garagething.hostname:}")
	private String webthingHostname;
	
	@Value("${garagething.webthing.port:8888}")
	private int port;
	
	@Bean
	public GpioController gpio() throws PlatformAlreadyAssignedException {
		if (platform != null) {
			logger.info("Selected platform {}", platform);
			PlatformManager.setPlatform(platform);
		} else {
			logger.info("Using default platform. Use property 'garagething.pi4j.platform' to customise.");
		}
		
		return GpioFactory.getInstance();
	}
	
	@Bean
	public String hostname() {
		try {
			return StringUtils.hasText(webthingHostname) ? webthingHostname : InetAddress.getLocalHost().getHostName() + ".local";
		} catch (UnknownHostException e) {
			throw new IllegalStateException("Local host name could not be resolved into an address", e);
		}
	}
	
	@Bean
	public WebThingServer webThingServer(GpioController gpio) {
		Platform pi4jPlatform = PlatformManager.getPlatform();
		logger.info("Platform is: {}", pi4jPlatform);
		
		Thing doorThing = new DoorThing(gpio, doorSensorWiringPiAddress, relayWiringPiAddress);
		WebThingServer server;
		
		String hostname = hostname();
		logger.info("Using hostname: {} and port: {}", hostname, port);
		
		try {
			// If adding more than one thing, use MultipleThings() with a name.
			// In the single thing case, the thing's name will be broadcast.
			server = new WebThingServer(new WebThingServer.SingleThing(doorThing), port, hostname);
			
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					server.stop();
				} catch (Exception e) {
					logger.warn("Error while stopping server.", e);
				}
			}));
			
			server.start(false);
			logger.info("Started server with port={}", server.getListeningPort());
			return server;
		} catch (IOException e) {
			throw new IllegalStateException("Server startup error: "+e.toString(), e);
		}
	}
	
}
