package io.openems.edge.batteryinverter.sinexcel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OptionsEnum;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.BatteryInverterConstraint;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.batteryinverter.sinexcel.enums.EnableDisable;
import io.openems.edge.batteryinverter.sinexcel.enums.PowerRisingMode;
import io.openems.edge.batteryinverter.sinexcel.statemachine.Context;
import io.openems.edge.batteryinverter.sinexcel.statemachine.StateMachine;
import io.openems.edge.batteryinverter.sinexcel.statemachine.StateMachine.State;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverterChain;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.BitsWordElement;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Battery-Inverter.Sinexcel", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
})
public class SinexcelImpl extends AbstractOpenemsModbusComponent
		implements Sinexcel, OffGridBatteryInverter, ManagedSymmetricBatteryInverter, SymmetricBatteryInverter,
		ModbusComponent, OpenemsComponent, TimedataProvider, StartStoppable {

	private final Logger log = LoggerFactory.getLogger(SinexcelImpl.class);

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	private final CalculateEnergyFromPower calculateChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricBatteryInverter.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricBatteryInverter.ChannelId.ACTIVE_DISCHARGE_ENERGY);

	public static final int MAX_APPARENT_POWER = 30_000;
	public static final int DEFAULT_UNIT_ID = 1;

	private static final int MAX_CURRENT = 90; // [A]
	public static final int DEFAULT_EMS_TIMEOUT = 60;
	public static final int DEFAULT_BMS_TIMEOUT = 0;
	public static final EnableDisable DEFAULT_GRID_EXISTENCE_DETECTION_ON = EnableDisable.DISABLE;
	public static final PowerRisingMode DEFAULT_POWER_RISING_MODE = PowerRisingMode.STEP;
	private static final int MAX_TOPPING_CHARGE_VOLTAGE = 750;

	/**
	 * Manages the {@link State}s of the StateMachine.
	 */
	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);

	@Reference
	protected ComponentManager componentManager;

	@Reference
	protected ConfigurationAdmin cm;

	@Reference
	private Power power;

	protected Config config;

	public SinexcelImpl() throws OpenemsNamedException {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricBatteryInverter.ChannelId.values(), //
				ManagedSymmetricBatteryInverter.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				OffGridBatteryInverter.ChannelId.values(), //
				Sinexcel.ChannelId.values() //
		);
		this._setMaxApparentPower(SinexcelImpl.MAX_APPARENT_POWER);
	}

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Activate
	protected void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), DEFAULT_UNIT_ID, this.cm, "Modbus",
				config.modbus_id())) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run(Battery battery, int setActivePower, int setReactivePower) throws OpenemsNamedException {
		// Store the current State
		this.channel(Sinexcel.ChannelId.STATE_MACHINE).setNextValue(this.stateMachine.getCurrentState());

		// Initialize 'Start-Stop' Channel
		this._setStartStop(StartStop.UNDEFINED);

		// Set Default Settings
		this.setDefaultSettings();

		// Set Battery Limits
		this.setBatteryLimits(battery);

		// Calculate the Energy values from ActivePower.
		this.calculateEnergy();

		// Prepare Context
		var context = new Context(this, this.config, this.targetGridMode.get(), setActivePower, setReactivePower);

		// Call the StateMachine
		try {
			this.stateMachine.run(context);

			this.channel(Sinexcel.ChannelId.RUN_FAILED).setNextValue(false);

		} catch (OpenemsNamedException e) {
			this.channel(Sinexcel.ChannelId.RUN_FAILED).setNextValue(true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
		}
	}

	/**
	 * Updates the Channel if its current value is not equal to the new value.
	 *
	 * @param channelId Sinexcel Channel-Id
	 * @param newValue  {@link OptionsEnum} value.
	 * @throws IllegalArgumentException on error
	 */
	private void updateIfNotEqual(Sinexcel.ChannelId channelId, OptionsEnum value)
			throws IllegalArgumentException, OpenemsNamedException {
		this.updateIfNotEqual(channelId, value.getValue());
	}

	/**
	 * Updates the Channel if its current value is not equal to the new value.
	 *
	 * @param channelId Sinexcel Channel-Id
	 * @param newValue  Integer value.
	 * @throws IllegalArgumentException on error
	 */
	private void updateIfNotEqual(Sinexcel.ChannelId channelId, Integer newValue) throws IllegalArgumentException {
		WriteChannel<Integer> channel = this.channel(channelId);
		var currentValue = channel.value();
		if (currentValue.isDefined() && !Objects.equals(currentValue.get(), newValue)) {
			try {
				channel.setNextWriteValue(newValue);
			} catch (OpenemsNamedException e) {
				this.logWarn(this.log, "Unable to update Channel [" + channel.address() + "] from [" + currentValue
						+ "] to [" + newValue + "]");
				e.printStackTrace();
			}
		}
	}

	/**
	 * Sets some default settings on the inverter, like Timeout.
	 *
	 * @throws OpenemsNamedException on error
	 */
	private void setDefaultSettings() throws OpenemsNamedException {
		this.updateIfNotEqual(Sinexcel.ChannelId.EMS_TIMEOUT, DEFAULT_EMS_TIMEOUT);
		this.updateIfNotEqual(Sinexcel.ChannelId.BMS_TIMEOUT, DEFAULT_BMS_TIMEOUT);
		this.updateIfNotEqual(Sinexcel.ChannelId.GRID_EXISTENCE_DETECTION_ON, DEFAULT_GRID_EXISTENCE_DETECTION_ON);
		this.updateIfNotEqual(Sinexcel.ChannelId.POWER_RISING_MODE, DEFAULT_POWER_RISING_MODE);
	}

	/**
	 * Sets the Battery Limits.
	 *
	 * @param battery the linked {@link Battery}
	 * @throws OpenemsNamedException on error
	 */
	private void setBatteryLimits(Battery battery) throws OpenemsNamedException {
		// Upper voltage limit of battery protection >= Topping charge voltage >= Float
		// charge voltage >= Lower voltage limit of battery protection (814 >= 809 >=
		// 808 >= 813).
		// Discharge Min Voltage
		this.updateIfNotEqual(Sinexcel.ChannelId.DISCHARGE_MIN_VOLTAGE, battery.getDischargeMinVoltage().get());

		// Charge Max Voltage
		this.updateIfNotEqual(Sinexcel.ChannelId.CHARGE_MAX_VOLTAGE, battery.getChargeMaxVoltage().get());

		// Topping Charge Voltage
		this.updateIfNotEqual(Sinexcel.ChannelId.TOPPING_CHARGE_VOLTAGE,
				TypeUtils.min(battery.getChargeMaxVoltage().get(), MAX_TOPPING_CHARGE_VOLTAGE));

		// Float Charge Voltage
		this.updateIfNotEqual(Sinexcel.ChannelId.FLOAT_CHARGE_VOLTAGE,
				TypeUtils.min(battery.getChargeMaxVoltage().get(), MAX_TOPPING_CHARGE_VOLTAGE));

		// Discharge Max Current
		// negative value is corrected as zero
		this.updateIfNotEqual(Sinexcel.ChannelId.DISCHARGE_MAX_CURRENT,
				TypeUtils.fitWithin(0 /* enforce positive */, MAX_CURRENT, battery.getDischargeMaxCurrent().orElse(0)));

		// Charge Max Current
		// negative value is corrected as zero
		this.updateIfNotEqual(Sinexcel.ChannelId.CHARGE_MAX_CURRENT,
				TypeUtils.fitWithin(0 /* enforce positive */, MAX_CURRENT, battery.getChargeMaxCurrent().orElse(0)));
	}

	@Override
	public String debugLog() {
		return this.stateMachine.getCurrentState().asCamelCase() //
				+ "|" + this.getGridModeChannel().value().asOptionString();
	}

	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);

	@Override
	public void setStartStop(StartStop value) {
		if (this.startStopTarget.getAndSet(value) != value) {
			// Set only if value changed
			this.stateMachine.forceNextState(State.UNDEFINED);
		}
	}

	public StartStop getStartStopTarget() {
		switch (this.config.startStop()) {
		case AUTO:
			// read StartStop-Channel
			return this.startStopTarget.get();

		case START:
			// force START
			return StartStop.START;

		case STOP:
			// force STOP
			return StartStop.STOP;
		}

		assert false;
		return StartStop.UNDEFINED; // can never happen
	}

	protected final AtomicReference<TargetGridMode> targetGridMode = new AtomicReference<>(TargetGridMode.GO_ON_GRID);

	@Override
	public void setTargetGridMode(TargetGridMode targetGridMode) {
		if (this.targetGridMode.getAndSet(targetGridMode) != targetGridMode) {
			// Set only if value changed
			this.stateMachine.forceNextState(State.UNDEFINED);
		}
	}

	@Override
	public BatteryInverterConstraint[] getStaticConstraints() throws OpenemsException {
		if (this.stateMachine.getCurrentState() == State.RUNNING) {
			return BatteryInverterConstraint.NO_CONSTRAINTS;

		}
		// Block any power as long as we are not RUNNING
		return new BatteryInverterConstraint[] { //
				new BatteryInverterConstraint("Sinexcel inverter not ready", Phase.ALL, Pwr.REACTIVE, //
						Relationship.EQUALS, 0d), //
				new BatteryInverterConstraint("Sinexcel inverter not ready", Phase.ALL, Pwr.ACTIVE, //
						Relationship.EQUALS, 0d) //
		};
	}

	@Override
	public int getPowerPrecision() {
		return 100;
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				SymmetricBatteryInverter.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricBatteryInverter.getModbusSlaveNatureTable(accessMode) //
		);
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() throws OpenemsException {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(1, Priority.HIGH, //
						m(Sinexcel.ChannelId.MANUFACTURER_AND_MODEL_NUMBER, //
								new StringWordElement(1, 16)), //
						m(Sinexcel.ChannelId.SERIAL_NUMBER, new StringWordElement(17, 8)), //
						new DummyRegisterElement(25, 31), //
						m(new BitsWordElement(32, this) //
								.bit(0, Sinexcel.ChannelId.FAULT_STATUS) //
								.bit(1, Sinexcel.ChannelId.ALERT_STATUS) //
								.bit(2, Sinexcel.ChannelId.BATTERY_INVERTER_STATE) //
								.bit(3, Sinexcel.ChannelId.INVERTER_GRID_MODE) //
								.bit(4, Sinexcel.ChannelId.ISLAND_MODE) //
								.bit(5, Sinexcel.ChannelId.DERATING_STATUS) //
								.bit(6, Sinexcel.ChannelId.ALLOW_GRID_CONNECTION) //
								.bit(7, Sinexcel.ChannelId.STANDBY_STATUS))), //
				new FC3ReadRegistersTask(33, Priority.LOW, //
						m(new BitsWordElement(33, this) //
								.bit(0, Sinexcel.ChannelId.OBTAIN_FAULT_RECORD_FLAG) //
								.bit(1, Sinexcel.ChannelId.WRITE_POWER_GENERATION_INTO_EEPROM) //
								.bit(2, Sinexcel.ChannelId.INITIALIZE_DSP_PARAMETERS) //
								.bit(3, Sinexcel.ChannelId.MASTER_SLAVE_MODE)), //
						new DummyRegisterElement(34, 35), //
						m(new BitsWordElement(36, this) //
								.bit(0, Sinexcel.ChannelId.AC_OVER_VOLTAGE_PROTECTION) //
								.bit(1, Sinexcel.ChannelId.AC_UNDER_VOLTAGE_PROTECTION) //
								.bit(2, Sinexcel.ChannelId.AC_OVER_FREQUENCY_PROTECTION) //
								.bit(3, Sinexcel.ChannelId.AC_UNDER_FREQUENCY_PROTECTION) //
								.bit(4, Sinexcel.ChannelId.GRID_VOLTAGE_UNBALANCE) //
								.bit(5, Sinexcel.ChannelId.GRID_PHASE_REVERSE) //
								.bit(6, Sinexcel.ChannelId.INVERTER_ISLAND) //
								.bit(7, Sinexcel.ChannelId.ON_GRID_OFF_GRID_SWITCH_OVER_FAILURE) //
								.bit(8, Sinexcel.ChannelId.OUTPUT_GROUND_FAULT) //
								.bit(9, Sinexcel.ChannelId.GRID_PHASE_LOCK_FAILED) //
								.bit(10, Sinexcel.ChannelId.INTERNAL_AIR_OVER_TEMPERATURE) //
								.bit(11, Sinexcel.ChannelId.GRID_CONNECTED_CONDITION_TIME_OUT) //
								.bit(12, Sinexcel.ChannelId.MODULE_RENUMBER_FAILURE) //
								.bit(13, Sinexcel.ChannelId.CANB_COMMUNICATION_FAILURE) //
								.bit(14, Sinexcel.ChannelId.POWER_FREQUENCY_SYNCHRONIZATION_FAILURE) //
								.bit(15, Sinexcel.ChannelId.CARRIER_SYNCHRONIZATION_FALURE)), //
						m(new BitsWordElement(37, this) //
								.bit(0, Sinexcel.ChannelId.EPO_ERROR) //
								.bit(1, Sinexcel.ChannelId.MONITOR_PARAMETER_MISMATCH) //
								.bit(2, Sinexcel.ChannelId.DSP_VERSION_ABNORMAL) //
								.bit(3, Sinexcel.ChannelId.CPLD_VERSION_ERROR) //
								.bit(4, Sinexcel.ChannelId.HARDWARE_VERSION_ERROR) //
								.bit(5, Sinexcel.ChannelId.CANA_COMMUNICATION_FAILURE) //
								.bit(6, Sinexcel.ChannelId.AUXILARY_POWER_FAULT) //
								.bit(7, Sinexcel.ChannelId.FAN_FAILURE) //
								.bit(8, Sinexcel.ChannelId.DC_OVER_VOLTAGE) //
								.bit(9, Sinexcel.ChannelId.DC_LOW_VOLTAGE) //
								.bit(10, Sinexcel.ChannelId.DC_VOLTAGE_UNBALANCED) //
								.bit(12, Sinexcel.ChannelId.AC_RELAY_SHORT_CIRCUIT) //
								.bit(13, Sinexcel.ChannelId.OUTPUT_VOLTAGE_ABNORMAL) //
								.bit(14, Sinexcel.ChannelId.OUTPUT_CURRENT_UNBALANCED) //
								.bit(15, Sinexcel.ChannelId.OVER_TEMPERATURE_OF_HEAT_SINK)), //
						m(new BitsWordElement(38, this) //
								.bit(0, Sinexcel.ChannelId.OUTPUT_OVER_LOAD_TOT) //
								.bit(1, Sinexcel.ChannelId.GRID_CONTINUE_OVER_VOLTAGE) //
								.bit(2, Sinexcel.ChannelId.AC_SOFT_START_FAILURE) //
								.bit(3, Sinexcel.ChannelId.INVERTER_START_FAILURE) //
								.bit(4, Sinexcel.ChannelId.AC_RELAY_IS_OPEN) //
								.bit(5, Sinexcel.ChannelId.U2_BOARD_COMMUNICATION_IS_ABNORMAL) //
								.bit(6, Sinexcel.ChannelId.AC_DC_COMPONENT_EXCESS) //
								.bit(7, Sinexcel.ChannelId.MASTER_SLAVE_SAMPLING_ABNORMALITY) //
								.bit(8, Sinexcel.ChannelId.PARAMETER_SETTING_ERROR) //
								.bit(9, Sinexcel.ChannelId.LOW_OFF_GRID_ENERGY) //
								.bit(10, Sinexcel.ChannelId.N_LINE_IS_NOT_CONNECTED) //
								.bit(11, Sinexcel.ChannelId.STANDBY_BUS_HEIGHT) //
								.bit(12, Sinexcel.ChannelId.SINGLE_PHASE_WIRING_ERROR) //
								.bit(13, Sinexcel.ChannelId.EXCESSIVE_GRID_FREQUENCY_CHANGE) //
								.bit(14, Sinexcel.ChannelId.ABRUPT_PHASE_ANGLE_FAULT_OF_POWER_GRID) //
								.bit(15, Sinexcel.ChannelId.GRID_CONNECTION_PARAMETER_CONFLICT)), //
						m(new BitsWordElement(39, this) //
								.bit(0, Sinexcel.ChannelId.EE_READING_ERROR_1) //
								.bit(1, Sinexcel.ChannelId.EE_READING_ERROR_2) //
								.bit(2, Sinexcel.ChannelId.FLASH_READING_ERROR) //
								.bit(3, Sinexcel.ChannelId.INVERTER_OVER_LOAD) //
								.bit(4, Sinexcel.ChannelId.BATTERY_PARAMETER_SETTING_ERROR) //
								.bit(5, Sinexcel.ChannelId.SLAVE_LOST_ALARM)), //
						m(new BitsWordElement(40, this) //
								.bit(0, Sinexcel.ChannelId.DC_CHARGING) //
								.bit(1, Sinexcel.ChannelId.DC_DISCHARGING) //
								.bit(2, Sinexcel.ChannelId.BATTERY_FULLY_CHARGED) //
								.bit(3, Sinexcel.ChannelId.BATTERY_EMPTY) //
								.bit(4, Sinexcel.ChannelId.DC_FAULT_STATUS) //
								.bit(5, Sinexcel.ChannelId.DC_ALERT_STATUS)), //
						new DummyRegisterElement(41, 43), //
						m(new BitsWordElement(44, this) //
								.bit(0, Sinexcel.ChannelId.DC_INPUT_OVER_VOLTAGE_PROTECTION) //
								.bit(1, Sinexcel.ChannelId.DC_INPUT_UNDER_VOLTAGE_PROTECTION) //
								.bit(3, Sinexcel.ChannelId.BMS_ALERT) //
								.bit(4, Sinexcel.ChannelId.BMS_COMMUNICATION_TIMEOUT) //
								.bit(5, Sinexcel.ChannelId.EMS_COMMUNICATION_TIMEOUT)), //
						m(new BitsWordElement(45, this) //
								.bit(0, Sinexcel.ChannelId.DC_SOFT_START_FAILED) //
								.bit(1, Sinexcel.ChannelId.DC_RELAY_SHORT_CIRCUIT) //
								.bit(2, Sinexcel.ChannelId.DC_RELAY_SHORT_OPEN) //
								.bit(3, Sinexcel.ChannelId.BATTERY_POWER_OVER_LOAD) //
								.bit(4, Sinexcel.ChannelId.DC_BUS_STARTING_FAILED) //
								.bit(5, Sinexcel.ChannelId.DC_QUICK_CHECK_OVER_CURRENT)), //
						new DummyRegisterElement(46), //
						m(new BitsWordElement(47, this) //
								.bit(0, Sinexcel.ChannelId.DC_OC)) //

				),

				new FC3ReadRegistersTask(101, Priority.LOW, //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_L1, new SignedWordElement(101),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_L2, new SignedWordElement(102),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_L3, new SignedWordElement(103),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.GRID_CURRENT_L1, new SignedWordElement(104),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.GRID_CURRENT_L2, new SignedWordElement(105),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.GRID_CURRENT_L3, new SignedWordElement(106),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.FREQUENCY, new SignedWordElement(107),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						new DummyRegisterElement(108, 109),
						m(Sinexcel.ChannelId.ACTIVE_POWER_L1, new SignedWordElement(110),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.ACTIVE_POWER_L2, new SignedWordElement(111),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.ACTIVE_POWER_L3, new SignedWordElement(112),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.REACTIVE_POWER_L1, new SignedWordElement(113),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.REACTIVE_POWER_L2, new SignedWordElement(114),
								ElementToChannelConverter.SCALE_FACTOR_1), // fems
						m(Sinexcel.ChannelId.REACTIVE_POWER_L3, new SignedWordElement(115),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.APPERENT_POWER_L1, new SignedWordElement(116),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.APPERENT_POWER_L2, new SignedWordElement(117),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.APPERENT_POWER_L3, new SignedWordElement(118),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.COS_PHI_L1, new SignedWordElement(119),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.COS_PHI_L2, new SignedWordElement(120),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.COS_PHI_L3, new SignedWordElement(121),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(SymmetricBatteryInverter.ChannelId.ACTIVE_POWER, new SignedWordElement(122),
								new ElementToChannelConverterChain(ElementToChannelConverter.SCALE_FACTOR_1,
										IGNORE_LESS_THAN_100)), //
						m(SymmetricBatteryInverter.ChannelId.REACTIVE_POWER, new SignedWordElement(123),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.APPARENT_POWER, new SignedWordElement(124),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.COS_PHI, new SignedWordElement(125),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						new DummyRegisterElement(126, 129), //
						m(Sinexcel.ChannelId.REACTIVE_ENERGY, new UnsignedDoublewordElement(130),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
						m(Sinexcel.ChannelId.TEMPERATURE_OF_AC_HEAT_SINK, new SignedWordElement(132)), //
						m(Sinexcel.ChannelId.DC_VOLTAGE_POSITIVE, new SignedWordElement(133),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.DC_VOLTAGE_NEGATIVE, new SignedWordElement(134),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(135),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(136),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.SET_OFF_GRID_VOLTAGE, new SignedWordElement(137),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.SET_OFF_GRID_FREQUENCY, new SignedWordElement(138),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						new DummyRegisterElement(139, 140),
						m(Sinexcel.ChannelId.DC_POWER, new SignedWordElement(141),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(Sinexcel.ChannelId.DC_VOLTAGE, new SignedWordElement(142),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.DC_CURRENT, new SignedWordElement(143),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.DC_CHARGE_ENERGY, new UnsignedDoublewordElement(144),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.DC_DISCHARGE_ENERGY, new UnsignedDoublewordElement(146),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.TEMPERATURE_OF_DC_DC_HEAT_SINK, new SignedWordElement(148)) //
				),

				new FC3ReadRegistersTask(224, Priority.LOW, //
						m(Sinexcel.ChannelId.DC_RELAY_REAR_END_VOLTAGE, new SignedWordElement(224),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.CHARGE_MAX_CURRENT_READ, new UnsignedWordElement(225),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(Sinexcel.ChannelId.DISCHARGE_MAX_CURRENT_READ, new UnsignedWordElement(226),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						new DummyRegisterElement(227, 299),
						m(Sinexcel.ChannelId.IP_ADDRESS_BLOCK_1, new UnsignedWordElement(300)), //
						m(Sinexcel.ChannelId.IP_ADDRESS_BLOCK_2, new UnsignedWordElement(301)), //
						m(Sinexcel.ChannelId.IP_ADDRESS_BLOCK_3, new UnsignedWordElement(302)), //
						m(Sinexcel.ChannelId.IP_ADDRESS_BLOCK_4, new UnsignedWordElement(303)), //
						m(Sinexcel.ChannelId.NETMASK_BLOCK_1, new UnsignedWordElement(304)), //
						m(Sinexcel.ChannelId.NETMASK_BLOCK_2, new UnsignedWordElement(305)), //
						m(Sinexcel.ChannelId.NETMASK_BLOCK_3, new UnsignedWordElement(306)), //
						m(Sinexcel.ChannelId.NETMASK_BLOCK_4, new UnsignedWordElement(307)), //
						m(Sinexcel.ChannelId.GATEWAY_IP_BLOCK_1, new UnsignedWordElement(308)), //
						m(Sinexcel.ChannelId.GATEWAY_IP_BLOCK_2, new UnsignedWordElement(309)), //
						m(Sinexcel.ChannelId.GATEWAY_IP_BLOCK_3, new UnsignedWordElement(310)), //
						m(Sinexcel.ChannelId.GATEWAY_IP_BLOCK_4, new UnsignedWordElement(311)), //
						m(Sinexcel.ChannelId.MAC, new StringWordElement(312, 3)), //
						new DummyRegisterElement(315), //
						m(Sinexcel.ChannelId.MODBUS_UNIT_ID, new UnsignedWordElement(316)), //
						new DummyRegisterElement(317, 319),
						m(Sinexcel.ChannelId.BAUDRATE, new UnsignedWordElement(320)), //
						new DummyRegisterElement(321, 324),
						m(Sinexcel.ChannelId.INTERFACE_TYPE, new UnsignedWordElement(325)), //
						m(Sinexcel.ChannelId.COMMUNICATION_PROTOCOL_SELECTION, new UnsignedWordElement(326)), //
						m(Sinexcel.ChannelId.EMS_TIMEOUT, new UnsignedWordElement(327)), //
						m(Sinexcel.ChannelId.EPO_ENABLE, new UnsignedWordElement(328)), //
						m(Sinexcel.ChannelId.BMS_TIMEOUT, new UnsignedWordElement(329)), //
						m(Sinexcel.ChannelId.BMS_PROTOCOL_SELECTION, new UnsignedWordElement(330)), //
						m(Sinexcel.ChannelId.SET_GRID_MODE, new UnsignedWordElement(331)), //
						m(Sinexcel.ChannelId.BUZZER_ENABLE, new UnsignedWordElement(332)), //
						m(Sinexcel.ChannelId.RESTORE_FACTORY_SETTING, new UnsignedWordElement(333)) //
				),

				new FC3ReadRegistersTask(650, Priority.LOW, //
						m(Sinexcel.ChannelId.START_INVERTER, new UnsignedWordElement(650)), //
						m(Sinexcel.ChannelId.STOP_INVERTER, new UnsignedWordElement(651)), //
						m(Sinexcel.ChannelId.CLEAR_FAILURE, new UnsignedWordElement(652)), //
						m(Sinexcel.ChannelId.SET_ON_GRID_MODE, new UnsignedWordElement(653)), //
						m(Sinexcel.ChannelId.SET_OFF_GRID_MODE, new UnsignedWordElement(654)), //
						m(Sinexcel.ChannelId.SET_STANDBY_COMMAND, new UnsignedWordElement(655)), //
						m(Sinexcel.ChannelId.SET_SOFT_START, new UnsignedWordElement(656)), //
						m(Sinexcel.ChannelId.RESET_INSTRUCTION, new UnsignedWordElement(657)), //
						m(Sinexcel.ChannelId.GRID_STOP, new UnsignedWordElement(658)) //
				),

				new FC3ReadRegistersTask(748, Priority.LOW, //
						m(Sinexcel.ChannelId.OUTPUT_VOLTAGE_LEVEL, new UnsignedWordElement(748)), //
						m(Sinexcel.ChannelId.OUTPUT_FREQUENCY_LEVEL, new UnsignedWordElement(749)), //
						m(Sinexcel.ChannelId.INVERTER_WIRING_TOPOLOGY, new UnsignedWordElement(750)), //
						new DummyRegisterElement(751),
						m(Sinexcel.ChannelId.SWITCHING_DEVICE_ACCESS_SETTING, new UnsignedWordElement(752)), //
						m(Sinexcel.ChannelId.MODULE_POWER_LEVEL, new UnsignedWordElement(753)), //
						m(Sinexcel.ChannelId.DC_VOLTAGE_LEVEL, new UnsignedWordElement(754)), //
						m(Sinexcel.ChannelId.CPU_TYPE, new UnsignedWordElement(755)), //
						m(Sinexcel.ChannelId.OFF_GRID_AND_PARALLEL_ENABLE, new UnsignedWordElement(756)), //
						m(Sinexcel.ChannelId.SET_DC_SOFT_START_EXTERNAL_CONTROL, new UnsignedWordElement(757)), //
						new DummyRegisterElement(758, 767),
						m(Sinexcel.ChannelId.GRID_OVER_VOLTAGE_PROTECTION_AMPLITUDE, new SignedWordElement(768)), //
						m(Sinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_TIME_1, new SignedWordElement(769)), //
						m(Sinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_LEVEL_2, new SignedWordElement(770)), //
						m(Sinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_TIME_2, new SignedWordElement(771)), //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_1, new SignedWordElement(772)), //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_1, new SignedWordElement(773)), //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_2, new SignedWordElement(774)), //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_2, new SignedWordElement(775)), //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_3, new SignedWordElement(776)), //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_3, new SignedWordElement(777)), //

						m(Sinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_LEVEL_1, new SignedWordElement(778)), //
						m(Sinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_TIME_1, new SignedWordElement(779)), //
						m(Sinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_LEVEL_2, new SignedWordElement(780)), //
						m(Sinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_TIME_2, new SignedWordElement(781)), //

						m(Sinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_LEVEL_1, new SignedWordElement(782)), //
						m(Sinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_TIME_1, new SignedWordElement(783)), //
						m(Sinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_LEVEL_2, new SignedWordElement(784)), //
						m(Sinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_TIME_2, new SignedWordElement(785)), //

						m(Sinexcel.ChannelId.RECONNECT_TIME, new SignedWordElement(786)), //
						new DummyRegisterElement(787, 789),
						m(Sinexcel.ChannelId.ANTI_ISLANDING, new UnsignedWordElement(790)), //

						m(Sinexcel.ChannelId.FREQUENCY_VOLTAGE_RIDE_THROUGH, new UnsignedWordElement(791)), //
						m(Sinexcel.ChannelId.REACTIVE_POWER_CONTROL_MODE, new UnsignedWordElement(792)), //
						m(Sinexcel.ChannelId.POWER_RISING_MODE, new UnsignedWordElement(793)), //
						m(Sinexcel.ChannelId.ACTIVE_POWER_CONTROL_MODE, new UnsignedWordElement(794)), //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_ASYMMETRIC_DETECTON, new UnsignedWordElement(795)), //
						m(Sinexcel.ChannelId.CONTINUOUS_OVERVOLTAGE_DETECTION, new UnsignedWordElement(796)), //
						m(Sinexcel.ChannelId.GRID_EXISTENCE_DETECTION_ON, new UnsignedWordElement(797)), //
						m(Sinexcel.ChannelId.NEUTRAL_FLOATING_DETECTION, new UnsignedWordElement(798)), //
						m(Sinexcel.ChannelId.OFF_GRID_BLACKSTART_MODE, new UnsignedWordElement(799)), //
						m(Sinexcel.ChannelId.GRID_CODE_SELCETION, new UnsignedWordElement(800)), //
						m(Sinexcel.ChannelId.GRID_CONNECTED_ACTIVE_CAPACITY_LIMITATION_FUNCTION,
								new UnsignedWordElement(801)), //
						m(Sinexcel.ChannelId.GRID_ACTIVE_POWER_CAPACITY_SETTING, new UnsignedWordElement(802)), //
						m(Sinexcel.ChannelId.SINGLE_PHASE_MODE_SELECTION, new UnsignedWordElement(803)), //
						m(Sinexcel.ChannelId.OVER_VOLTAGE_DROP_ACTIVE, new UnsignedWordElement(804)), //
						m(Sinexcel.ChannelId.START_UP_MODE, new UnsignedWordElement(805)), //
						new DummyRegisterElement(806),
						m(Sinexcel.ChannelId.LOCAL_ID_SETTING, new SignedWordElement(807)), //
						m(Sinexcel.ChannelId.FLOAT_CHARGE_VOLTAGE, new SignedWordElement(808),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
						m(Sinexcel.ChannelId.TOPPING_CHARGE_VOLTAGE, new SignedWordElement(809),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
						m(Sinexcel.ChannelId.CURRENT_FROM_TOPPING_CHARGING_TO_FLOAT_CHARGING,
								new SignedWordElement(810), ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
						m(Sinexcel.ChannelId.CHARGE_MAX_CURRENT, new SignedWordElement(811),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
						m(Sinexcel.ChannelId.DISCHARGE_MAX_CURRENT, new SignedWordElement(812),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
						m(Sinexcel.ChannelId.DISCHARGE_MIN_VOLTAGE, new SignedWordElement(813),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
						m(Sinexcel.ChannelId.CHARGE_MAX_VOLTAGE, new SignedWordElement(814),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
						m(Sinexcel.ChannelId.BATTERY_VOLTAGE_PROTECTION_LIMIT, new UnsignedWordElement(815),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1) //

				),

				new FC3ReadRegistersTask(825, Priority.LOW, //
						m(Sinexcel.ChannelId.LEAKAGE_CURRENT_DC_COMPONENT_DETECTOR, new UnsignedWordElement(825)), //
						new DummyRegisterElement(826, 845),
						m(Sinexcel.ChannelId.RESUME_AND_LIMIT_FREQUENCY, new SignedWordElement(846)), //
						m(Sinexcel.ChannelId.RESTORE_LOWER_FREQUENCY_OF_GRID_CONNECTION, new SignedWordElement(847)), //
						m(Sinexcel.ChannelId.VOLTAGE_REACTIVE_REFERENCE, new UnsignedWordElement(848)), //
						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V1,
								new UnsignedWordElement(849)), //
						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V2,
								new SignedWordElement(850)), //
						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V3,
								new SignedWordElement(851)), //
						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V4,
								new SignedWordElement(852)), //

						m(Sinexcel.ChannelId.MAX_CAPACITIVE_REACTIVE_REGULATION_Q1, new SignedWordElement(853)), //
						m(Sinexcel.ChannelId.INITIAL_CAPACITIVE_REACTIVE_REGULATION_Q2, new SignedWordElement(854)), //
						m(Sinexcel.ChannelId.INITIAL_INDUCTIVE_REACTIVE_REGULATION_Q3, new SignedWordElement(855)), //
						m(Sinexcel.ChannelId.MAX_INDUCTIVE_REACTIVE_REGULATION_Q4, new SignedWordElement(856)), //

						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_RESPONSE_TIME, new SignedWordElement(857)), //
						m(Sinexcel.ChannelId.REACTIVE_FIRST_ORDER_RESPONSE_TIME, new SignedWordElement(858)), //
						new DummyRegisterElement(859, 861),
						m(Sinexcel.ChannelId.INITIAL_VOLTAGE_V_START, new SignedWordElement(862)), //
						m(Sinexcel.ChannelId.END_VOLTAGE_V_STOP, new SignedWordElement(863)), //
						m(Sinexcel.ChannelId.INITIAL_POWER_P_START, new SignedWordElement(864)), //
						m(Sinexcel.ChannelId.END_POWER_P_STOP, new SignedWordElement(865)), //
						m(Sinexcel.ChannelId.RETURN_TO_SERVICE_DELAY, new SignedWordElement(866)), //
						m(Sinexcel.ChannelId.VOLT_WATT_RESPONSE_TIME, new SignedWordElement(867)), //
						m(Sinexcel.ChannelId.START_OF_FREQUENY_DROP, new SignedWordElement(868)), //
						m(Sinexcel.ChannelId.SLOPE_OF_FREQUENCY_DROP, new SignedWordElement(869)), //
						m(Sinexcel.ChannelId.FREQUENCY_WATT_F_STOP_DISCHARGE, new SignedWordElement(870)), //
						m(Sinexcel.ChannelId.FREQUENCY_WATT_F_STOP_CHARGE, new SignedWordElement(871)), //
						m(Sinexcel.ChannelId.VOLT_WATT_V_START_CHARGE, new SignedWordElement(872)), //
						new DummyRegisterElement(873, 875),
						m(Sinexcel.ChannelId.SOFT_START_RAMP_RATE, new SignedWordElement(876)), //
						m(Sinexcel.ChannelId.POWER_RAMP_RATE, new SignedWordElement(877)), //
						m(Sinexcel.ChannelId.POWER_FACTOR_SETTING, new SignedWordElement(878),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.POWER_FACTOR_P1, new SignedWordElement(879),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.POWER_FACTOR_P2, new SignedWordElement(880),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.POWER_FACTOR_P3, new SignedWordElement(881),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.POWER_FACTOR_P4, new SignedWordElement(882),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P1, new SignedWordElement(883),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P2, new SignedWordElement(884),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P3, new SignedWordElement(885),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P4, new SignedWordElement(886),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.CONTINUOS_OVER_VOLTAGE_TRIP_THRESHOLD, new SignedWordElement(887),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(Sinexcel.ChannelId.FREQUENCY_VARIATION_RATE_TRIP_THRESHOLD, new SignedWordElement(888)), //
						m(Sinexcel.ChannelId.PHASE_ANGLE_ABRUPT_TRIP_THRESHOLD, new SignedWordElement(889)), //
						m(Sinexcel.ChannelId.GRID_RECONNECTION_VOLTAGE_UPPER_LIMIT, new SignedWordElement(890)), //
						m(Sinexcel.ChannelId.GRID_RECONNECTION_VOLTAGE_LOWER_LIMIT, new SignedWordElement(891)), //
						m(Sinexcel.ChannelId.GRID_RECONNECTION_FREQUENCY_UPPER_LIMIT, new SignedWordElement(892)), //
						m(Sinexcel.ChannelId.GRID_RECONNECTION_FREQUENCY_LOWER_LIMIT, new SignedWordElement(893)), //
						m(Sinexcel.ChannelId.LOW_FREQUENCY_RAMP_RATE, new SignedWordElement(894)) //
				),

				new FC3ReadRegistersTask(934, Priority.LOW, //
						m(Sinexcel.ChannelId.METER_ACTIVE_POWER, new SignedWordElement(934),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						new DummyRegisterElement(935, 947),
						m(Sinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L1, new UnsignedWordElement(948),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L2, new UnsignedWordElement(949),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L3, new UnsignedWordElement(950),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L1, new UnsignedWordElement(951),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L2, new UnsignedWordElement(952),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L3, new UnsignedWordElement(953),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L1_PARAMETERS_1, new UnsignedWordElement(954),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L2_PARAMETERS_1, new UnsignedWordElement(955),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L3_PARAMETERS_1, new UnsignedWordElement(956),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L1_PARAMETERS_2, new UnsignedWordElement(957),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L2_PARAMETERS_2, new UnsignedWordElement(958),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L3_PARAMETERS_2, new UnsignedWordElement(959),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L1, new UnsignedWordElement(960),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L2, new UnsignedWordElement(961),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L3, new UnsignedWordElement(962),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.POSITIVE_BUS_VOLTAGE_CALIBRATION, new UnsignedWordElement(963),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.NEGATIVE_BUS_VOLTAGE_CALIBRATION, new UnsignedWordElement(964),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.DC_VOLTAGE_CALIBRATION, new UnsignedWordElement(965),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.DC_CURRENT_CALIBRATION, new UnsignedWordElement(966),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(Sinexcel.ChannelId.DC_INDUCTOR_CURRENT_CALIBRATION, new UnsignedWordElement(967),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3) //
				),

				new FC6WriteRegisterTask(135, //
						m(Sinexcel.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(135),
								ElementToChannelConverter.SCALE_FACTOR_2)),
				new FC6WriteRegisterTask(136, //
						m(Sinexcel.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(136),
								ElementToChannelConverter.SCALE_FACTOR_2)),
				new FC6WriteRegisterTask(137, //
						m(Sinexcel.ChannelId.SET_OFF_GRID_VOLTAGE, new SignedWordElement(137),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(138, //
						m(Sinexcel.ChannelId.SET_OFF_GRID_FREQUENCY, new SignedWordElement(138),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(300, //
						m(Sinexcel.ChannelId.IP_ADDRESS_BLOCK_1, new UnsignedWordElement(300))),
				new FC6WriteRegisterTask(301, //
						m(Sinexcel.ChannelId.IP_ADDRESS_BLOCK_2, new UnsignedWordElement(301))),
				new FC6WriteRegisterTask(302, //
						m(Sinexcel.ChannelId.IP_ADDRESS_BLOCK_3, new UnsignedWordElement(302))),
				new FC6WriteRegisterTask(303, //
						m(Sinexcel.ChannelId.IP_ADDRESS_BLOCK_4, new UnsignedWordElement(303))),
				new FC6WriteRegisterTask(304, //
						m(Sinexcel.ChannelId.NETMASK_BLOCK_1, new UnsignedWordElement(304))),
				new FC6WriteRegisterTask(305, //
						m(Sinexcel.ChannelId.NETMASK_BLOCK_2, new UnsignedWordElement(305))),
				new FC6WriteRegisterTask(306, //
						m(Sinexcel.ChannelId.NETMASK_BLOCK_3, new UnsignedWordElement(306))),
				new FC6WriteRegisterTask(307, //
						m(Sinexcel.ChannelId.NETMASK_BLOCK_4, new UnsignedWordElement(307))),
				new FC6WriteRegisterTask(308, //
						m(Sinexcel.ChannelId.GATEWAY_IP_BLOCK_1, new UnsignedWordElement(308))),
				new FC6WriteRegisterTask(309, //
						m(Sinexcel.ChannelId.GATEWAY_IP_BLOCK_2, new UnsignedWordElement(309))),
				new FC6WriteRegisterTask(310, //
						m(Sinexcel.ChannelId.GATEWAY_IP_BLOCK_3, new UnsignedWordElement(310))),
				new FC6WriteRegisterTask(311, //
						m(Sinexcel.ChannelId.GATEWAY_IP_BLOCK_4, new UnsignedWordElement(311))),
				new FC6WriteRegisterTask(312, //
						m(Sinexcel.ChannelId.MAC, new UnsignedWordElement(312))),
				new FC6WriteRegisterTask(316, //
						m(Sinexcel.ChannelId.MODBUS_UNIT_ID, new UnsignedWordElement(316))),
				new FC6WriteRegisterTask(320, //
						m(Sinexcel.ChannelId.BAUDRATE, new UnsignedWordElement(320))),
				new FC6WriteRegisterTask(325, //
						m(Sinexcel.ChannelId.INTERFACE_TYPE, new UnsignedWordElement(325))),
				new FC6WriteRegisterTask(326, //
						m(Sinexcel.ChannelId.COMMUNICATION_PROTOCOL_SELECTION, new UnsignedWordElement(326))),
				new FC6WriteRegisterTask(327, //
						m(Sinexcel.ChannelId.EMS_TIMEOUT, new UnsignedWordElement(327))),
				new FC6WriteRegisterTask(328, //
						m(Sinexcel.ChannelId.EPO_ENABLE, new UnsignedWordElement(328))),
				new FC6WriteRegisterTask(329, //
						m(Sinexcel.ChannelId.BMS_TIMEOUT, new UnsignedWordElement(329))),
				new FC6WriteRegisterTask(330, //
						m(Sinexcel.ChannelId.BMS_PROTOCOL_SELECTION, new UnsignedWordElement(330))),
				new FC6WriteRegisterTask(331, //
						m(Sinexcel.ChannelId.SET_GRID_MODE, new UnsignedWordElement(331))),
				new FC6WriteRegisterTask(332, //
						m(Sinexcel.ChannelId.BUZZER_ENABLE, new UnsignedWordElement(332))),
				new FC6WriteRegisterTask(333, //
						m(Sinexcel.ChannelId.RESTORE_FACTORY_SETTING, new UnsignedWordElement(333))),
				new FC6WriteRegisterTask(650, //
						m(Sinexcel.ChannelId.START_INVERTER, new UnsignedWordElement(650))),
				new FC6WriteRegisterTask(651, //
						m(Sinexcel.ChannelId.STOP_INVERTER, new UnsignedWordElement(651))),
				new FC6WriteRegisterTask(652, //
						m(Sinexcel.ChannelId.CLEAR_FAILURE, new UnsignedWordElement(652))),
				new FC6WriteRegisterTask(653, //
						m(Sinexcel.ChannelId.SET_ON_GRID_MODE, new UnsignedWordElement(653))),
				new FC6WriteRegisterTask(654, //
						m(Sinexcel.ChannelId.SET_OFF_GRID_MODE, new UnsignedWordElement(654))),
				new FC6WriteRegisterTask(655, //
						m(Sinexcel.ChannelId.SET_STANDBY_COMMAND, new UnsignedWordElement(655))),
				new FC6WriteRegisterTask(656, //
						m(Sinexcel.ChannelId.SET_SOFT_START, new UnsignedWordElement(656))),
				new FC6WriteRegisterTask(657, //
						m(Sinexcel.ChannelId.RESET_INSTRUCTION, new UnsignedWordElement(657))),
				new FC6WriteRegisterTask(658, //
						m(Sinexcel.ChannelId.GRID_STOP, new UnsignedWordElement(658))),
				new FC6WriteRegisterTask(752, //
						m(Sinexcel.ChannelId.SWITCHING_DEVICE_ACCESS_SETTING, new UnsignedWordElement(752))),
				new FC6WriteRegisterTask(755, //
						m(Sinexcel.ChannelId.CPU_TYPE, new UnsignedWordElement(755))),
				new FC6WriteRegisterTask(756, //
						m(Sinexcel.ChannelId.OFF_GRID_AND_PARALLEL_ENABLE, new UnsignedWordElement(756))),
				new FC6WriteRegisterTask(757, //
						m(Sinexcel.ChannelId.SET_DC_SOFT_START_EXTERNAL_CONTROL, new UnsignedWordElement(757))),
				new FC6WriteRegisterTask(768, //
						m(Sinexcel.ChannelId.GRID_OVER_VOLTAGE_PROTECTION_AMPLITUDE, new SignedWordElement(768))),
				new FC6WriteRegisterTask(769, //
						m(Sinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_TIME_1, new SignedWordElement(769))),
				new FC6WriteRegisterTask(770, //
						m(Sinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_LEVEL_2, new SignedWordElement(770))),
				new FC6WriteRegisterTask(771, //
						m(Sinexcel.ChannelId.AC_OVER_VOLTAGE_TRIP_TIME_2, new SignedWordElement(771))),
				new FC6WriteRegisterTask(772, //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_1, new SignedWordElement(772))),
				new FC6WriteRegisterTask(773, //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_1, new SignedWordElement(773))),
				new FC6WriteRegisterTask(774, //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_2, new SignedWordElement(774))),
				new FC6WriteRegisterTask(775, //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_2, new SignedWordElement(775))),
				new FC6WriteRegisterTask(776, //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_LEVEL_3, new SignedWordElement(776))),
				new FC6WriteRegisterTask(777, //
						m(Sinexcel.ChannelId.AC_UNDER_VOLTAGE_TRIP_TIME_3, new SignedWordElement(777))),
				new FC6WriteRegisterTask(778, //
						m(Sinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_LEVEL_1, new SignedWordElement(778))),
				new FC6WriteRegisterTask(779, //
						m(Sinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_TIME_1, new SignedWordElement(779))),
				new FC6WriteRegisterTask(780, //
						m(Sinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_LEVEL_2, new SignedWordElement(780))),
				new FC6WriteRegisterTask(781, //
						m(Sinexcel.ChannelId.AC_OVER_FREQUENCY_TRIP_TIME_2, new SignedWordElement(781))),
				new FC6WriteRegisterTask(782, //
						m(Sinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_LEVEL_1, new SignedWordElement(782))),
				new FC6WriteRegisterTask(783, //
						m(Sinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_TIME_1, new SignedWordElement(783))),
				new FC6WriteRegisterTask(784, //
						m(Sinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_LEVEL_2, new SignedWordElement(784))),
				new FC6WriteRegisterTask(785, //
						m(Sinexcel.ChannelId.AC_UNDER_FREQUENCY_TRIP_TIME_2, new SignedWordElement(785))),
				new FC6WriteRegisterTask(786, //
						m(Sinexcel.ChannelId.RECONNECT_TIME, new SignedWordElement(786))),
				new FC6WriteRegisterTask(790, //
						m(Sinexcel.ChannelId.ANTI_ISLANDING, new UnsignedWordElement(790))),
				new FC6WriteRegisterTask(791, //
						m(Sinexcel.ChannelId.FREQUENCY_VOLTAGE_RIDE_THROUGH, new UnsignedWordElement(791))),
				new FC6WriteRegisterTask(792, //
						m(Sinexcel.ChannelId.REACTIVE_POWER_CONTROL_MODE, new UnsignedWordElement(792))),
				new FC6WriteRegisterTask(793, //
						m(Sinexcel.ChannelId.POWER_RISING_MODE, new UnsignedWordElement(793))),
				new FC6WriteRegisterTask(794, //
						m(Sinexcel.ChannelId.ACTIVE_POWER_CONTROL_MODE, new UnsignedWordElement(794))),
				new FC6WriteRegisterTask(795, //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_ASYMMETRIC_DETECTON, new UnsignedWordElement(795))),
				new FC6WriteRegisterTask(796, //
						m(Sinexcel.ChannelId.CONTINUOUS_OVERVOLTAGE_DETECTION, new UnsignedWordElement(796))),
				new FC6WriteRegisterTask(797, //
						m(Sinexcel.ChannelId.GRID_EXISTENCE_DETECTION_ON, new UnsignedWordElement(797))),
				new FC6WriteRegisterTask(798, //
						m(Sinexcel.ChannelId.NEUTRAL_FLOATING_DETECTION, new UnsignedWordElement(798))),
				new FC6WriteRegisterTask(799, //
						m(Sinexcel.ChannelId.OFF_GRID_BLACKSTART_MODE, new UnsignedWordElement(799))),
				new FC6WriteRegisterTask(800, //
						m(Sinexcel.ChannelId.GRID_CODE_SELCETION, new UnsignedWordElement(800))),
				new FC6WriteRegisterTask(801, //
						m(Sinexcel.ChannelId.GRID_CONNECTED_ACTIVE_CAPACITY_LIMITATION_FUNCTION,
								new UnsignedWordElement(801))),
				new FC6WriteRegisterTask(802, //
						m(Sinexcel.ChannelId.GRID_ACTIVE_POWER_CAPACITY_SETTING, new UnsignedWordElement(802))),
				new FC6WriteRegisterTask(803, //
						m(Sinexcel.ChannelId.SINGLE_PHASE_MODE_SELECTION, new UnsignedWordElement(803))),
				new FC6WriteRegisterTask(804, //
						m(Sinexcel.ChannelId.OVER_VOLTAGE_DROP_ACTIVE, new UnsignedWordElement(804))),
				new FC6WriteRegisterTask(805, //
						m(Sinexcel.ChannelId.START_UP_MODE, new UnsignedWordElement(805))),
				new FC6WriteRegisterTask(807, //
						m(Sinexcel.ChannelId.LOCAL_ID_SETTING, new SignedWordElement(807))),
				new FC6WriteRegisterTask(808, //
						m(Sinexcel.ChannelId.FLOAT_CHARGE_VOLTAGE, new SignedWordElement(808),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(809, //
						m(Sinexcel.ChannelId.TOPPING_CHARGE_VOLTAGE, new SignedWordElement(809),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(810, //
						m(Sinexcel.ChannelId.CURRENT_FROM_TOPPING_CHARGING_TO_FLOAT_CHARGING,
								new SignedWordElement(810), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(811, //
						m(Sinexcel.ChannelId.CHARGE_MAX_CURRENT, new SignedWordElement(811),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(812, //
						m(Sinexcel.ChannelId.DISCHARGE_MAX_CURRENT, new SignedWordElement(812),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(813, //
						m(Sinexcel.ChannelId.DISCHARGE_MIN_VOLTAGE, new SignedWordElement(813),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(814, //
						m(Sinexcel.ChannelId.CHARGE_MAX_VOLTAGE, new SignedWordElement(814),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(815, //
						m(Sinexcel.ChannelId.BATTERY_VOLTAGE_PROTECTION_LIMIT, new UnsignedWordElement(815),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC6WriteRegisterTask(825, //
						m(Sinexcel.ChannelId.LEAKAGE_CURRENT_DC_COMPONENT_DETECTOR, new UnsignedWordElement(825))),
				// TODO Check scale factors, channel names and so on...
				new FC6WriteRegisterTask(846, //
						m(Sinexcel.ChannelId.RESUME_AND_LIMIT_FREQUENCY, new SignedWordElement(846))),
				new FC6WriteRegisterTask(847, //
						m(Sinexcel.ChannelId.RESTORE_LOWER_FREQUENCY_OF_GRID_CONNECTION, new SignedWordElement(847))),
				new FC6WriteRegisterTask(848, //
						m(Sinexcel.ChannelId.VOLTAGE_REACTIVE_REFERENCE, new SignedWordElement(848))),
				new FC6WriteRegisterTask(849, //
						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V1,
								new SignedWordElement(849))),
				new FC6WriteRegisterTask(850, //
						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V2,
								new SignedWordElement(850))),
				new FC6WriteRegisterTask(851, //
						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V3,
								new SignedWordElement(851))),
				new FC6WriteRegisterTask(852, //
						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_POWER_ADJUSTMENT_POINT_V4,
								new SignedWordElement(852))),
				new FC6WriteRegisterTask(853, //
						m(Sinexcel.ChannelId.MAX_CAPACITIVE_REACTIVE_REGULATION_Q1, new SignedWordElement(853))),
				new FC6WriteRegisterTask(854, //
						m(Sinexcel.ChannelId.INITIAL_CAPACITIVE_REACTIVE_REGULATION_Q2, new SignedWordElement(854))),
				new FC6WriteRegisterTask(855, //
						m(Sinexcel.ChannelId.INITIAL_INDUCTIVE_REACTIVE_REGULATION_Q3, new SignedWordElement(855))),
				new FC6WriteRegisterTask(856, //
						m(Sinexcel.ChannelId.MAX_INDUCTIVE_REACTIVE_REGULATION_Q4, new SignedWordElement(856))),
				new FC6WriteRegisterTask(857, //
						m(Sinexcel.ChannelId.VOLTAGE_AND_REACTIVE_RESPONSE_TIME, new SignedWordElement(857))),
				new FC6WriteRegisterTask(858, //
						m(Sinexcel.ChannelId.REACTIVE_FIRST_ORDER_RESPONSE_TIME, new SignedWordElement(858))),
				new FC6WriteRegisterTask(862, //
						m(Sinexcel.ChannelId.INITIAL_VOLTAGE_V_START, new SignedWordElement(862))),
				new FC6WriteRegisterTask(863, //
						m(Sinexcel.ChannelId.END_VOLTAGE_V_STOP, new SignedWordElement(863))),
				new FC6WriteRegisterTask(864, //
						m(Sinexcel.ChannelId.INITIAL_POWER_P_START, new SignedWordElement(864))),
				new FC6WriteRegisterTask(865, //
						m(Sinexcel.ChannelId.END_POWER_P_STOP, new SignedWordElement(865))),
				new FC6WriteRegisterTask(866, //
						m(Sinexcel.ChannelId.RETURN_TO_SERVICE_DELAY, new SignedWordElement(866))),
				new FC6WriteRegisterTask(867, //
						m(Sinexcel.ChannelId.VOLT_WATT_RESPONSE_TIME, new SignedWordElement(867))),
				new FC6WriteRegisterTask(868, //
						m(Sinexcel.ChannelId.START_OF_FREQUENY_DROP, new SignedWordElement(868))),
				new FC6WriteRegisterTask(869, //
						m(Sinexcel.ChannelId.SLOPE_OF_FREQUENCY_DROP, new SignedWordElement(869))),
				new FC6WriteRegisterTask(870, //
						m(Sinexcel.ChannelId.FREQUENCY_WATT_F_STOP_DISCHARGE, new SignedWordElement(870))),
				new FC6WriteRegisterTask(871, //
						m(Sinexcel.ChannelId.FREQUENCY_WATT_F_STOP_CHARGE, new SignedWordElement(871))),
				new FC6WriteRegisterTask(872, //
						m(Sinexcel.ChannelId.VOLT_WATT_V_START_CHARGE, new SignedWordElement(872))),
				new FC6WriteRegisterTask(876, //
						m(Sinexcel.ChannelId.SOFT_START_RAMP_RATE, new SignedWordElement(876))),
				new FC6WriteRegisterTask(877, //
						m(Sinexcel.ChannelId.POWER_RAMP_RATE, new SignedWordElement(877))),
				new FC6WriteRegisterTask(878, //
						m(Sinexcel.ChannelId.POWER_FACTOR_SETTING, new SignedWordElement(878))),
				new FC6WriteRegisterTask(879, //
						m(Sinexcel.ChannelId.POWER_FACTOR_P1, new SignedWordElement(879))),
				new FC6WriteRegisterTask(880, //
						m(Sinexcel.ChannelId.POWER_FACTOR_P2, new SignedWordElement(880))),
				new FC6WriteRegisterTask(881, //
						m(Sinexcel.ChannelId.POWER_FACTOR_P3, new SignedWordElement(881))),
				new FC6WriteRegisterTask(882, //
						m(Sinexcel.ChannelId.POWER_FACTOR_P4, new SignedWordElement(882))),
				new FC6WriteRegisterTask(883, //
						m(Sinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P1, new SignedWordElement(883))),
				new FC6WriteRegisterTask(884, //
						m(Sinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P2, new SignedWordElement(884))),
				new FC6WriteRegisterTask(885, //
						m(Sinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P3, new SignedWordElement(885))),
				new FC6WriteRegisterTask(886, //
						m(Sinexcel.ChannelId.POWER_FACTOR_CURVE_MODE_P4, new SignedWordElement(886))),
				new FC6WriteRegisterTask(887, //
						m(Sinexcel.ChannelId.CONTINUOS_OVER_VOLTAGE_TRIP_THRESHOLD, new SignedWordElement(887))),
				new FC6WriteRegisterTask(888, //
						m(Sinexcel.ChannelId.FREQUENCY_VARIATION_RATE_TRIP_THRESHOLD, new SignedWordElement(888))),
				new FC6WriteRegisterTask(889, //
						m(Sinexcel.ChannelId.PHASE_ANGLE_ABRUPT_TRIP_THRESHOLD, new SignedWordElement(889))),
				new FC6WriteRegisterTask(890, //
						m(Sinexcel.ChannelId.GRID_RECONNECTION_VOLTAGE_UPPER_LIMIT, new SignedWordElement(890))),
				new FC6WriteRegisterTask(891, //
						m(Sinexcel.ChannelId.GRID_RECONNECTION_VOLTAGE_LOWER_LIMIT, new SignedWordElement(891))),
				new FC6WriteRegisterTask(892, //
						m(Sinexcel.ChannelId.GRID_RECONNECTION_FREQUENCY_UPPER_LIMIT, new SignedWordElement(892))),
				new FC6WriteRegisterTask(893, //
						m(Sinexcel.ChannelId.GRID_RECONNECTION_FREQUENCY_LOWER_LIMIT, new SignedWordElement(893))),
				new FC6WriteRegisterTask(894, //
						m(Sinexcel.ChannelId.LOW_FREQUENCY_RAMP_RATE, new SignedWordElement(894))),
				new FC6WriteRegisterTask(934, //
						m(Sinexcel.ChannelId.METER_ACTIVE_POWER, new SignedWordElement(934))),
				new FC6WriteRegisterTask(948, //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L1, new UnsignedWordElement(948),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(949, //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L2, new UnsignedWordElement(949),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(950, //
						m(Sinexcel.ChannelId.GRID_VOLTAGE_CALIBRATION_L3, new UnsignedWordElement(950),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(951, //
						m(Sinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L1, new UnsignedWordElement(951),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(952, //
						m(Sinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L2, new UnsignedWordElement(952),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(953, //
						m(Sinexcel.ChannelId.INVERTER_VOLTAGE_CALIBRATION_L3, new UnsignedWordElement(953),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(954, //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L1_PARAMETERS_1, new UnsignedWordElement(954),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(955, //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L2_PARAMETERS_1, new UnsignedWordElement(955),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(956, //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L3_PARAMETERS_1, new UnsignedWordElement(956),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(957, //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L1_PARAMETERS_2, new UnsignedWordElement(957),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(958, //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L2_PARAMETERS_2, new UnsignedWordElement(958),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(959, //
						m(Sinexcel.ChannelId.INDUCTOR_CURRENT_CALIBRATION_L3_PARAMETERS_2, new UnsignedWordElement(959),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(960, //
						m(Sinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L1, new UnsignedWordElement(960),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(961, //
						m(Sinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L2, new UnsignedWordElement(961),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(962, //
						m(Sinexcel.ChannelId.OUTPUT_CURRENT_CALIBRATION_L3, new UnsignedWordElement(962),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(963, //
						m(Sinexcel.ChannelId.POSITIVE_BUS_VOLTAGE_CALIBRATION, new UnsignedWordElement(963),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(964, //
						m(Sinexcel.ChannelId.NEGATIVE_BUS_VOLTAGE_CALIBRATION, new UnsignedWordElement(964),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(965, //
						m(Sinexcel.ChannelId.DC_VOLTAGE_CALIBRATION, new UnsignedWordElement(965),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(966, //
						m(Sinexcel.ChannelId.DC_CURRENT_CALIBRATION, new UnsignedWordElement(966),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(967, //
						m(Sinexcel.ChannelId.DC_INDUCTOR_CURRENT_CALIBRATION, new UnsignedWordElement(967),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC6WriteRegisterTask(4001, //
						m(Sinexcel.ChannelId.TIME_SETTING, new SignedWordElement(4001))),
				new FC6WriteRegisterTask(4007, //
						m(Sinexcel.ChannelId.PASSWORD, new SignedWordElement(4007)))

		); //

	}

	/**
	 * The Sinexcel Battery Inverter claims to outputting a little bit of power even
	 * if it does not. This little filter ignores values for ActivePower less than
	 * 100 (charge/discharge).
	 */
	private static final ElementToChannelConverter IGNORE_LESS_THAN_100 = new ElementToChannelConverter(//
			obj -> {
				if (obj == null) {
					return null;
				}
				int value = (Short) obj;
				if (Math.abs(value) < 100) {
					return 0;
				}
				return value;
			}, //
			value -> value);

	/**
	 * Calculate the Energy values from ActivePower.
	 */
	private void calculateEnergy() {
		// Calculate Energy
		var activePower = this.getActivePower().get();
		if (activePower == null) {
			// Not available
			this.calculateChargeEnergy.update(null);
			this.calculateDischargeEnergy.update(null);
		} else if (activePower > 0) {
			// Buy-From-Grid
			this.calculateChargeEnergy.update(0);
			this.calculateDischargeEnergy.update(activePower);
		} else {
			// Sell-To-Grid
			this.calculateChargeEnergy.update(activePower * -1);
			this.calculateDischargeEnergy.update(0);
		}
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
