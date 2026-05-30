import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.const import CONF_ID

# namespace and class definition
ble_remote_ns = cg.esphome_ns.namespace('ble_remote')
BLERemoteComponent = ble_remote_ns.class_('BLERemoteComponent', cg.Component)

CONFIG_SCHEMA = cv.Schema({
    cv.GenerateID(): cv.declare_id(BLERemoteComponent),
}).extend(cv.COMPONENT_SCHEMA)

async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    cg.add_library("nimble", None)