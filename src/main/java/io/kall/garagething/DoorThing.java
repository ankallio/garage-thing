package io.kall.garagething;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinPullResistance;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;

import io.webthings.webthing.Action;
import io.webthings.webthing.Event;
import io.webthings.webthing.Property;
import io.webthings.webthing.Thing;
import io.webthings.webthing.Value;

public class DoorThing extends Thing {
	
	private static final Logger logger = LoggerFactory.getLogger(DoorThing.class);

	private static final PinState RELAY_ACTIVE = PinState.LOW;
	private static final PinState RELAY_INACTIVE = PinState.getInverseState(RELAY_ACTIVE);
	
	private static final PinState DOOR_OPEN = PinState.HIGH;
	private static final PinState DOOR_CLOSED = PinState.getInverseState(DOOR_OPEN);
	
	private static final int DOOR_SENSOR_DEBOUNCE_MS = 1000;
	
	private final GpioPinDigitalInput doorSensorPin;
	private final GpioPinDigitalOutput relayPin;

	private Instant lastActivationTime = Instant.ofEpochMilli(0L);
	
	private final Value<Boolean> openValue;
	private final Value<Boolean> relayValue;
	private final Value<Integer> openDurationValue;
	
	/** When was door last opened */
	private Instant doorOpenedTime = null;
	private final Timer timer = new Timer(true);
	
	public DoorThing(GpioController gpio, int doorSensorWiringPiAddress, int relayWiringPiAddress) {
		super("urn:dev:ops:garagedoor",
				"Garage door", 
				new JSONArray(Arrays.asList("DoorSensor", "OnOffSwitch" )),
				"Door stuff");
		
		doorSensorPin = gpio.provisionDigitalInputPin(RaspiPin.getPinByAddress(doorSensorWiringPiAddress), "doorsensor", PinPullResistance.PULL_UP);
		doorSensorPin.setShutdownOptions(true);
		doorSensorPin.setDebounce(DOOR_SENSOR_DEBOUNCE_MS);
		
		relayPin = gpio.provisionDigitalOutputPin(RaspiPin.getPinByAddress(relayWiringPiAddress), "relay", RELAY_INACTIVE);
		relayPin.setShutdownOptions(true, RELAY_INACTIVE);
		
		// Description for DoorSensor which must be an OpenProperty
		JSONObject openDescription = new JSONObject()
				.put("@type", "OpenProperty")
				.put("title", "State")
				.put("type", "boolean")
				.put("description", "Whether the door is open")
				.put("readOnly", true);
		logger.info("Door sensor pin={}, state={}", doorSensorPin, doorSensorPin.getState());
		openValue = new Value<Boolean>(doorSensorPin.getState() == DOOR_OPEN); // Note: Gateway shows ... when starting, updates only after next state change?
		this.addProperty(new Property<>(this, "open", openValue, openDescription));
		
		// Description for relay state indicator. Since it is of type OnOffSwitch, type is OnOffProperty.
		JSONObject relayMetadata = new JSONObject()
				.put("@type", "OnOffProperty")
				.put("type", "boolean")
				.put("title", "Relay active")
				.put("description", "Door relay active status")
				.put("readOnly", true);
		relayValue = new Value<Boolean>(relayPin.getState() == RELAY_ACTIVE);
		this.addProperty(new Property<>(this, "relay", relayValue, relayMetadata));
		// TODO Relay value probably is unnecessary, could be removed
		
		JSONObject openDurationMetadata = new JSONObject()
				.put("@type", "LevelProperty")
				.put("type", "integer")
				.put("unit", "seconds")
				.put("minimum", 0)
				.put("maximum", 60 * 60) // 1 hour
				.put("title", "Open time")
				.put("description", "Door has been open this long")
				.put("readOnly", true);
		openDurationValue = new Value<Integer>(0);
		this.addProperty(new Property<>(this, "openduration", openDurationValue, openDurationMetadata));
		
		// Start a timer which updates open duration value when door is open.
		timer.scheduleAtFixedRate(doorOpenDurationUpdater, 0, 1_000);
		
		// Add action to operate the door / relay
		this.addAvailableAction(
				ActivateAction.NAME, 
				new JSONObject()
						.put("title", "Activate")
						.put("description", "Open, Close or Stop door"),
				ActivateAction.class);
		
		// Add events for door opening and closing
		this.addAvailableEvent(
				DoorOpenedEvent.NAME, 
				new JSONObject()
						.put("title", "Door opened")
						.put("description", "Previously closed door was opened"));
		
		this.addAvailableEvent(
				DoorClosedEvent.NAME, 
				new JSONObject()
						.put("title", "Door closed")
						.put("description", "Previously opened door was closed"));
		
		this.addAvailableEvent(
				ActivatedEvent.NAME, 
				new JSONObject().put("title", "Door activated").put("description", "Door relay was activated"));
		
		// Setup listener for door sensor pin which updates openValue and adds events
		doorSensorPin.addListener((GpioPinListenerDigital) this::onDoorSensorTriggered);
		
		relayPin.addListener((GpioPinListenerDigital) event -> onRelayStateChanged(event));
	}
	
	private final TimerTask doorOpenDurationUpdater = new TimerTask() {
		
		@Override
		public void run() {
			updateOpenDuration();
		}
	};

	private void onDoorSensorTriggered(GpioPinDigitalStateChangeEvent event) {
		// Sensor state changed. Door either started to open or closed.
		
		boolean opened = event.getState() == DOOR_OPEN;
		logger.info("Door sensor state changed to: {} ({})", (opened ? "Opened" : "Closed"), event.getState());
		
		// Update property value
		openValue.set(opened);
		
		// Add event to thing
		if (opened)
			this.addEvent(new DoorOpenedEvent(this));
		else
			this.addEvent(new DoorClosedEvent(this));
		
		// Update timer
		doorOpenedTime = opened ? Instant.now() : null;
		openDurationValue.set(0);
	}

	private void onRelayStateChanged(GpioPinDigitalStateChangeEvent event) {
		logger.info("Relay pin state changed to: {}", event.getState());
		boolean relayActivated = event.getState() == RELAY_ACTIVE;
		relayValue.set(relayActivated);
	}

	private void updateOpenDuration() {
		// If door is open and time of door opening is known, update the opened-duration value
		if (openValue.get()) {
			Optional.ofNullable(doorOpenedTime)
					.map(opened -> Duration.between(opened, Instant.now()))
					.map(Duration::getSeconds)
					.map(Long::intValue)
					.ifPresent(openDurationValue::set);
		}
	}
	
	// Public static because constructor is called with reflection
	public static class ActivateAction extends Action {
		
		private static final String NAME = "activate";
		private static final long RELAY_PULSE_DURATION = 500L;
		private final Duration MIN_TIME_BETWEEN_ACTIONS = Duration.ofMillis(RELAY_PULSE_DURATION * 2);

		// Constructor arguments have to be Thing and JSONObject, called with reflection
		public ActivateAction(Thing thing, JSONObject input) {
			super(UUID.randomUUID().toString(), thing, NAME, input);
		}
		
		@Override
		public void performAction() {
			DoorThing thing = (DoorThing) getThing();
			
			// Try to prevent commands coming in too fast 
			if (Duration.between(thing.lastActivationTime, Instant.now()).compareTo(MIN_TIME_BETWEEN_ACTIONS) < 0) {
				logger.info("Not enough time has passed since last activation, so skipping action.");
				return;
			} else {
				thing.lastActivationTime = Instant.now();
			}
			
			logger.info("Performing relay activation");
			
			thing.addEvent(new ActivatedEvent(thing));
			
			// Pulse relay for a short time to activate the door system
			thing.relayPin.pulse(RELAY_PULSE_DURATION, RELAY_ACTIVE);
		}
	}
	
	private static class DoorOpenedEvent extends Event<Void> {
		private static final String NAME = "Opened";

		public DoorOpenedEvent(Thing thing) {
			super(thing, NAME);
		}
	}
	
	private static class DoorClosedEvent extends Event<Void> {
		private static final String NAME = "Closed";

		public DoorClosedEvent(Thing thing) {
			super(thing, NAME);
		}
	}
	
	private static class ActivatedEvent extends Event<Void> {
		private static final String NAME = "Activated";

		public ActivatedEvent(Thing thing) {
			super(thing, NAME);
		}
	}
}
