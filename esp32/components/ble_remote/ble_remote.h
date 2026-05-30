#pragma once
#include "esphome/core/component.h"
#include "esphome/core/log.h"
#include "esphome/core/helpers.h"
#include "rom/tjpgd.h" 
#include <string>

extern "C" {
#include "host/ble_hs.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
}

namespace esphome {
namespace ble_remote {

static const char *TAG = "ble_remote";

static ble_uuid128_t MY_SVC_UUID = BLE_UUID128_INIT(0x01, 0x66, 0x76, 0x03, 0x18, 0x4a, 0x33, 0x9d, 0x52, 0x4b, 0x7d, 0xc2, 0x01, 0x20, 0xa1, 0x4f);
static ble_uuid128_t MY_CMD_UUID = BLE_UUID128_INIT(0x01, 0x66, 0x76, 0x03, 0x18, 0x4a, 0x33, 0x9d, 0x52, 0x4b, 0x7d, 0xc2, 0x04, 0x20, 0xa1, 0x4f);
static ble_uuid128_t MY_IMG_UUID = BLE_UUID128_INIT(0x01, 0x66, 0x76, 0x03, 0x18, 0x4a, 0x33, 0x9d, 0x52, 0x4b, 0x7d, 0xc2, 0x05, 0x20, 0xa1, 0x4f);

static uint16_t command_handle;
static uint16_t image_handle;
static uint16_t active_conn_hndl = BLE_HS_CONN_HANDLE_NONE;

class BLERemoteComponent;
static BLERemoteComponent *global_ble_remote = nullptr;

class BLERemoteComponent : public Component {
 public:
  uint16_t *get_display_buffer() { return this->display_buffer_; }
  float get_progress() { return this->current_progress_; }
  
  int get_page() { return this->current_page_; }
  std::string get_nav_turn() { return this->nav_turn_; }
  std::string get_nav_dist() { return this->nav_dist_; }
  std::string get_nav_street() { return this->nav_street_; }
  std::string get_nav_exit() { return this->nav_exit_; }
  
  bool get_alert_active() { 
      // clear alert after 30 seconds
      if (alert_active_ && (millis() - alert_timer_ > 30000)) {
          alert_active_ = false;
      }
      return alert_active_; 
  }

  void setup() override {
    global_ble_remote = this;
    this->display_buffer_ = (uint16_t *)heap_caps_malloc(259200, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    this->jpg_input_buffer_ = (uint8_t *)heap_caps_malloc(40000, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    
    if (this->display_buffer_) memset(this->display_buffer_, 0, 259200);
    this->set_timeout(2000, [this]() { this->init_nimble(); });
  }

  void handle_touch(int x, int y) {
      uint32_t now = millis();
      
      if (now - last_touch_time_ > 400) {
          last_touch_time_ = now;
          
          if (y > 240) {
              this->current_page_ = 0; // page 0 - media
          } 
          else if (y < 120) {
              this->current_page_ = 1; // page 1 - navigation
          } 
          else if (x > 240) {
              if (this->current_page_ == 0) send_prev();
          } 
          else if (x < 120) {
              if (this->current_page_ == 0) send_next();
          } 
          else {
              if (this->current_page_ == 0) send_play_pause();
          }
      }
  }

  void loop() override {
    if (this->receiving_image_ && (millis() - this->last_rx_time_ > 1000)) {
        ESP_LOGW(TAG, "BLE transfer stalled. Forcing decode.");
        this->receiving_image_ = false;
        decode_jpg();
    }
  }

  static UINT jpg_get_data(JDEC *decoder, BYTE *buf, UINT len) {
    BLERemoteComponent *self = (BLERemoteComponent *)decoder->device;
    if (self->jpg_read_ptr_ + len > self->jpg_pos_) len = self->jpg_pos_ - self->jpg_read_ptr_;
    if (buf) memcpy(buf, self->jpg_input_buffer_ + self->jpg_read_ptr_, len);
    self->jpg_read_ptr_ += len;
    return len;
  }

  static UINT jpg_output_cb(JDEC *decoder, void *bitmap, JRECT *rect) {
    BLERemoteComponent *self = (BLERemoteComponent *)decoder->device;
    uint8_t *in = (uint8_t *)bitmap;
    for (int y = rect->top; y <= rect->bottom; y++) {
        for (int x = rect->left; x <= rect->right; x++) {
            if (x < 360 && y < 360) {
                uint8_t r = *in++;
                uint8_t g = *in++;
                uint8_t b = *in++;
                uint16_t color565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
                self->display_buffer_[y * 360 + x] = color565;
            }
        }
    }
    return 1;
  }

  void decode_jpg() {
    JDEC decoder;
    uint8_t *work = (uint8_t *)malloc(3100); 
    this->jpg_read_ptr_ = 0;
    
    if (jd_prepare(&decoder, jpg_get_data, work, 3100, this) == JDR_OK) {
        if (jd_decomp(&decoder, jpg_output_cb, 0) == JDR_OK) {
            ESP_LOGI(TAG, "JPG Decode Success");
            this->current_image_hash_ = this->pending_image_hash_;
        } else {
            ESP_LOGE(TAG, "Corrupt Image Data! Wiping Screen.");
            memset(this->display_buffer_, 0, 259200); 
        }
    } else {
        ESP_LOGE(TAG, "Invalid Image Header! Wiping Screen.");
        memset(this->display_buffer_, 0, 259200); 
    }
    free(work);
  }

  static int gatt_access_cb(uint16_t conn_handle, uint16_t attr_handle, struct ble_gatt_access_ctxt *ctxt, void *arg) {
    if (attr_handle == image_handle && global_ble_remote) {
        uint16_t data_len = OS_MBUF_PKTLEN(ctxt->om);
        ble_hs_mbuf_to_flat(ctxt->om, global_ble_remote->jpg_input_buffer_ + global_ble_remote->jpg_pos_, data_len, NULL);
        global_ble_remote->jpg_pos_ += data_len;
        
        global_ble_remote->last_rx_time_ = millis();
        global_ble_remote->receiving_image_ = true; 
        
    } else if (attr_handle == command_handle && global_ble_remote) {
        // street names
        uint8_t cmd_buf[128]; 
        uint16_t data_len = OS_MBUF_PKTLEN(ctxt->om);
        if (data_len > 127) data_len = 127; // prevent overflow
        
        ble_hs_mbuf_to_flat(ctxt->om, cmd_buf, data_len, NULL);
        cmd_buf[data_len] = '\0';
        std::string cmd((char*)cmd_buf, data_len);
        
        if (cmd.rfind("H:", 0) == 0) {
            int incoming_hash = std::stoi(cmd.substr(2));
            if (incoming_hash != global_ble_remote->current_image_hash_ && !global_ble_remote->receiving_image_) {
                std::string req = "REQ_IMG:" + std::to_string(incoming_hash);
                global_ble_remote->send_cmd(req.c_str());
            }
        }
        else if (cmd.rfind("START:", 0) == 0) {
            global_ble_remote->pending_image_hash_ = std::stoi(cmd.substr(6));
            global_ble_remote->jpg_pos_ = 0;
            global_ble_remote->last_rx_time_ = millis();
            global_ble_remote->receiving_image_ = true;
        }
        else if (cmd == "DECODE_NOW") {
            global_ble_remote->receiving_image_ = false;
            global_ble_remote->decode_jpg();
        }
        else if (cmd.rfind("T:", 0) == 0) {
            int percent = std::stoi(cmd.substr(2));
            global_ble_remote->current_progress_ = percent / 100.0f;
        }
        // hazard / police alerts
        else if (cmd.rfind("A:", 0) == 0) {
            std::string alert_type = cmd.substr(2);
            ESP_LOGI(TAG, "BLE ALERT RECEIVED: %s", alert_type.c_str());
            if (alert_type == "POLICE") {
                global_ble_remote->alert_active_ = true;
                global_ble_remote->alert_timer_ = millis();
            } else if (alert_type == "CLEAR") {
                global_ble_remote->alert_active_ = false;
            }
        }
        // navigation data parsing
        else if (cmd.rfind("N:", 0) == 0) {
            ESP_LOGI(TAG, "BLE NAV RECEIVED: %s", cmd.c_str());
            std::string payload = cmd.substr(2);
            size_t pos1 = payload.find('|');
            size_t pos2 = payload.find('|', pos1 + 1);
            size_t pos3 = payload.find('|', pos2 + 1);
            
            if (pos1 != std::string::npos && pos2 != std::string::npos) {
                global_ble_remote->nav_turn_ = payload.substr(0, pos1);
                global_ble_remote->nav_dist_ = payload.substr(pos1 + 1, pos2 - pos1 - 1);
                
                if (pos3 != std::string::npos) {
                    global_ble_remote->nav_street_ = payload.substr(pos2 + 1, pos3 - pos2 - 1);
                    global_ble_remote->nav_exit_ = payload.substr(pos3 + 1);
                } else {
                    global_ble_remote->nav_street_ = payload.substr(pos2 + 1);
                    global_ble_remote->nav_exit_ = "";
                }
            }
        }
    }
    return 0;
  }

  // BLE advertising
  void start_advertise() {
    struct ble_hs_adv_fields fields;
    memset(&fields, 0, sizeof(fields));
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)"Guition-Remote";
    fields.name_len = 14;
    ble_gap_adv_set_fields(&fields);
    struct ble_gap_adv_params adv_params;
    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    ble_gap_adv_start(BLE_OWN_ADDR_PUBLIC, NULL, BLE_HS_FOREVER, &adv_params, 
        [](struct ble_gap_event *event, void *arg) {
            if (event->type == BLE_GAP_EVENT_CONNECT) active_conn_hndl = event->connect.conn_handle;
            else if (event->type == BLE_GAP_EVENT_DISCONNECT) {
                active_conn_hndl = BLE_HS_CONN_HANDLE_NONE;
                if (global_ble_remote) global_ble_remote->start_advertise();
            }
            return 0;
        }, nullptr);
  }

  void init_nimble() {
    nimble_port_init();
    ble_hs_cfg.sync_cb = []() { if (global_ble_remote) global_ble_remote->start_advertise(); };
    static struct ble_gatt_chr_def chars[] = {
        {.uuid = (ble_uuid_t *)&MY_CMD_UUID, .access_cb = gatt_access_cb, .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_NOTIFY, .val_handle = &command_handle},
        {.uuid = (ble_uuid_t *)&MY_IMG_UUID, .access_cb = gatt_access_cb, .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP, .val_handle = &image_handle},
        {0}
    };
    static struct ble_gatt_svc_def gatt_svr_svcs[] = {
        {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = (ble_uuid_t *)&MY_SVC_UUID, .characteristics = chars},
        {0}
    };
    ble_svc_gap_init(); ble_svc_gatt_init();
    ble_gatts_count_cfg(gatt_svr_svcs); ble_gatts_add_svcs(gatt_svr_svcs);
    nimble_port_freertos_init([](void *p) { nimble_port_run(); });
  }

  void send_next() { send_cmd(">"); }
  void send_prev() { send_cmd("<"); }
  void send_play_pause() { send_cmd("P"); }

 private:
  uint16_t *display_buffer_{nullptr};
  uint8_t *jpg_input_buffer_{nullptr};
  uint32_t jpg_pos_ = 0;
  uint32_t jpg_read_ptr_ = 0;
  float current_progress_ = 0.0f;
  
  uint32_t last_rx_time_ = 0;
  bool receiving_image_ = false;
  
  int current_image_hash_ = 0;
  int pending_image_hash_ = 0;

  uint32_t last_touch_time_ = 0;
  int current_page_ = 0; 
  
  // initial navigation page values
  std::string nav_turn_ = "READY";
  std::string nav_dist_ = "--";
  std::string nav_street_ = "Awaiting GPS";
  std::string nav_exit_ = "";
  
  bool alert_active_ = false;
  uint32_t alert_timer_ = 0;

  void send_cmd(const char* c) {
    if (active_conn_hndl != BLE_HS_CONN_HANDLE_NONE) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(c, strlen(c));
        ble_gatts_notify_custom(active_conn_hndl, command_handle, om);
    }
  }
};

} // namespace ble_remote
} // namespace esphome