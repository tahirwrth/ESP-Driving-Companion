#pragma once
#include "esphome/core/component.h"
#include "esphome/core/log.h"
#include "esphome/core/helpers.h"
#include <JPEGDEC.h> // ⚡ THE V8 ENGINE ⚡
#include <string>
#include <stdio.h>
#include <stdlib.h>

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
  static int JPEGDraw(JPEGDRAW *pDraw) {
      if (!global_ble_remote) return 0;
      
      uint16_t *buf = global_ble_remote->get_display_buffer();
      uint16_t *pixels = pDraw->pPixels;
      
      int x = pDraw->x;
      int y = pDraw->y;
      int w = pDraw->iWidth;
      int h = pDraw->iHeight;
      
      for (int i = 0; i < h; i++) {
          int dest_y = y + i;
          if (dest_y >= 360) break;
          
          for (int j = 0; j < w; j++) {
              int dest_x = x + j;
              if (dest_x >= 360) break;
              
              buf[dest_y * 360 + dest_x] = pixels[i * w + j];
          }
      }
      return 1; // return 1 to keep decoding
  }

  uint16_t *get_display_buffer() { return this->display_buffer_; }
  float get_progress() { return this->current_progress_; }
  
  int get_page() { return this->current_page_; }
  std::string get_nav_turn() { return this->nav_turn_; }
  std::string get_nav_dist() { return this->nav_dist_; }
  std::string get_nav_street() { return this->nav_street_; }
  std::string get_nav_exit() { return this->nav_exit_; }
  
  int get_current_speed() { return this->current_speed_; }
  int get_speed_limit() { return this->speed_limit_; }
  int get_camera_distance() { return this->camera_distance_; }
  
  bool get_alert_active() { 
      if (alert_active_ && (millis() - alert_timer_ > 30000)) {
          alert_active_ = false;
      }
      return alert_active_; 
  }

  bool is_sleeping() {
      return (millis() - last_touch_time_ > 60000); 
  }

  void setup() override {
    global_ble_remote = this;
    
    setenv("SDL_NOMOUSE", "1", 1);
    setenv("SDL_MOUSEDEV", "/dev/null", 1);
    setenv("SDL_MOUSEDRV", "dummy", 1);

    this->display_buffer_ = (uint16_t *)heap_caps_malloc(259200, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    this->jpg_input_buffer_ = (uint8_t *)heap_caps_malloc(250000, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT); 
    if (this->display_buffer_) memset(this->display_buffer_, 0, 259200);

    this->anim_cache_ = (uint8_t*)heap_caps_malloc(3000000, MALLOC_CAP_SPIRAM);

    this->set_timeout(4000, [this]() {
        ESP_LOGI(TAG, "Trying to load animation into PSRAM");
        FILE* f = fopen("/sdcard/anim.mjpeg", "rb"); 
        if (!f) f = fopen("/anim.mjpeg", "rb");

        if (f && this->anim_cache_) {
            this->anim_total_size_ = fread(this->anim_cache_, 1, 3000000, f);
            fclose(f); 
            ESP_LOGI(TAG, "Successfully loaded %zu bytes into PSRAM Cache", this->anim_total_size_);
        } else {
            ESP_LOGE(TAG, "Failed to find anim.mjpeg on the SD card");
        }
    });

    this->last_touch_time_ = millis();
    this->set_timeout(2000, [this]() { this->init_nimble(); });
  }

  void handle_touch(int x, int y) {
      uint32_t now = millis();
      
    // pages in the interface are vertically stacked
    // to change pages touch or swipe from the top or bottom of the screen

      if (now - last_touch_time_ > 400) {
          last_touch_time_ = now;
          
          if (y > 240) {
              this->current_page_ = 0; 
          } 
          else if (y < 120) {
              if (this->current_page_ == 0) this->current_page_ = 1;
              else if (this->current_page_ == 1) this->current_page_ = 2;
              else if (this->current_page_ == 2) this->current_page_ = 3;
              else if (this->current_page_ == 3) this->current_page_ = 0;
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
        this->receiving_image_ = false;
        if (this->expected_image_size_ > 0 && this->jpg_pos_ != this->expected_image_size_) {
            ESP_LOGE(TAG, "BLE transfer did not match expected size, missing bytes (%d/%d)", 
                     this->jpg_pos_, this->expected_image_size_);
        } else {
            decode_jpg();
        }
    }
    // animation playback
    if (this->current_page_ == 3 && this->anim_total_size_ > 0 && !this->is_sleeping()) {
        if (millis() - last_frame_time_ >= 40) {
            
            bool found_frame = false;
            size_t search_pos = this->anim_play_pos_;

            while (search_pos < this->anim_total_size_ - 1) {
                if (this->anim_cache_[search_pos] == 0xFF && this->anim_cache_[search_pos+1] == 0xD8) {
                    size_t frame_start = search_pos;
                    size_t next_frame_start = this->anim_total_size_; 
                    
                    for (size_t j = frame_start + 2; j < this->anim_total_size_ - 1; j++) {
                        if (this->anim_cache_[j] == 0xFF && this->anim_cache_[j+1] == 0xD8) {
                            next_frame_start = j;
                            break;
                        }
                    }

                    size_t frame_size = next_frame_start - frame_start;
                    
                    if (frame_size <= 250000) { 
                        memcpy(this->jpg_input_buffer_, &this->anim_cache_[frame_start], frame_size);
                        this->jpg_pos_ = frame_size;
                        this->decode_jpg(); 
                        found_frame = true;
                        this->anim_play_pos_ = next_frame_start; 
                    } else {
                        this->anim_play_pos_ = next_frame_start; 
                    }
                    break; 
                }
                search_pos++;
            }

            if (!found_frame) {
                this->anim_play_pos_ = 0;
            }

            last_frame_time_ = millis(); 
        }
    }
  }

  void decode_jpg() {
      // decoder pointed directly to buffer memory for better performance
      if (jpeg_.openRAM(this->jpg_input_buffer_, this->jpg_pos_, JPEGDraw)) {
          jpeg_.setPixelType(RGB565_LITTLE_ENDIAN);
          if (jpeg_.decode(0, 0, 0)) {
              this->current_image_hash_ = this->pending_image_hash_;
          } else {
              ESP_LOGE(TAG, "Image corrupt, clearing screen");
          }
          jpeg_.close();
      } else {
          ESP_LOGE(TAG, "Invalid image header, clearing screen");
      }
  }

  static int gatt_access_cb(uint16_t conn_handle, uint16_t attr_handle, struct ble_gatt_access_ctxt *ctxt, void *arg) {
    if (attr_handle == image_handle && global_ble_remote) {
        uint16_t data_len = OS_MBUF_PKTLEN(ctxt->om);
        ble_hs_mbuf_to_flat(ctxt->om, global_ble_remote->jpg_input_buffer_ + global_ble_remote->jpg_pos_, data_len, NULL);
        global_ble_remote->jpg_pos_ += data_len;
        
        global_ble_remote->last_rx_time_ = millis();
        global_ble_remote->receiving_image_ = true; 
        
    } else if (attr_handle == command_handle && global_ble_remote) {
        uint8_t cmd_buf[128]; 
        uint16_t data_len = OS_MBUF_PKTLEN(ctxt->om);
        if (data_len > 127) data_len = 127; 
        
        ble_hs_mbuf_to_flat(ctxt->om, cmd_buf, data_len, NULL);
        cmd_buf[data_len] = '\0';
        std::string cmd((char*)cmd_buf, data_len);
        
        // check song change via image hash, request new image if different
        // originally image was always sent but now hash is used for better bandwidth management
        if (cmd.rfind("H:", 0) == 0) {
            int incoming_hash = std::stoi(cmd.substr(2));
            if (incoming_hash != global_ble_remote->current_image_hash_ && !global_ble_remote->receiving_image_) {
                std::string req = "REQ_IMG:" + std::to_string(incoming_hash);
                global_ble_remote->send_cmd(req.c_str());
            }
        }
        // start image download command
        else if (cmd.rfind("START:", 0) == 0) {
            std::string payload = cmd.substr(6);
            size_t pipe_pos = payload.find('|');
            
            if (pipe_pos != std::string::npos) {
                global_ble_remote->pending_image_hash_ = std::stoi(payload.substr(0, pipe_pos));
                global_ble_remote->expected_image_size_ = std::stoi(payload.substr(pipe_pos + 1));
            } else {
                global_ble_remote->pending_image_hash_ = std::stoi(payload);
                global_ble_remote->expected_image_size_ = 0; 
            }
            
            global_ble_remote->jpg_pos_ = 0;
            global_ble_remote->last_rx_time_ = millis();
            global_ble_remote->receiving_image_ = true;
        }
        // image decode and display commmand 
        else if (cmd == "DECODE_NOW") {
            global_ble_remote->receiving_image_ = false;
            
            if (global_ble_remote->expected_image_size_ > 0 && 
                global_ble_remote->jpg_pos_ != global_ble_remote->expected_image_size_) {
                ESP_LOGE(TAG, "BLE packet dropped, stopping");
            } else {
                global_ble_remote->decode_jpg();
            }
        }
        // music progress bar data
        else if (cmd.rfind("T:", 0) == 0) {
            int percent = std::stoi(cmd.substr(2));
            global_ble_remote->current_progress_ = percent / 100.0f;
        }
        // alert data i.e., police
        else if (cmd.rfind("A:", 0) == 0) {
            std::string alert_type = cmd.substr(2);
            if (alert_type == "POLICE") {
                global_ble_remote->alert_active_ = true;
                global_ble_remote->alert_timer_ = millis();
            } else if (alert_type == "CLEAR") {
                global_ble_remote->alert_active_ = false;
            }
        }
        // navigation data
        else if (cmd.rfind("N:", 0) == 0) {
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
        // GPS data i.e., speed, speed limit, speed cameras
        else if (cmd.rfind("G:", 0) == 0) {
            std::string payload = cmd.substr(2);
            size_t pos1 = payload.find('|');
            size_t pos2 = payload.find('|', pos1 + 1);
            
            if (pos1 != std::string::npos && pos2 != std::string::npos) {
                global_ble_remote->current_speed_ = std::stoi(payload.substr(0, pos1));
                global_ble_remote->speed_limit_ = std::stoi(payload.substr(pos1 + 1, pos2 - pos1 - 1));
                global_ble_remote->camera_distance_ = std::stoi(payload.substr(pos2 + 1));
            }
        }
    }
    return 0;
  }

  // ble advertising
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
        {.uuid = (ble_uuid_t *)&MY_CMD_UUID, .access_cb = gatt_access_cb, .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_NOTIFY, .val_handle = &command_handle},
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
  float current_progress_ = 0.0f;
  
  uint32_t last_rx_time_ = 0;
  bool receiving_image_ = false;
  
  int current_image_hash_ = 0;
  int pending_image_hash_ = 0;
  int expected_image_size_ = 0;

  uint32_t last_touch_time_ = 0;
  int current_page_ = 0; 
  
  std::string nav_turn_ = "READY";
  std::string nav_dist_ = "--";
  std::string nav_street_ = "Awaiting GPS";
  std::string nav_exit_ = "";
  
  int current_speed_ = 0;
  int speed_limit_ = 0;
  int camera_distance_ = -1;

  bool alert_active_ = false;
  uint32_t alert_timer_ = 0;

  uint8_t* anim_cache_{nullptr};
  size_t anim_total_size_ = 0;
  size_t anim_play_pos_ = 0;
  uint32_t last_frame_time_ = 0;

  JPEGDEC jpeg_;

  void send_cmd(const char* c) {
    if (active_conn_hndl != BLE_HS_CONN_HANDLE_NONE) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(c, strlen(c));
        ble_gatts_notify_custom(active_conn_hndl, command_handle, om);
    }
  }
};

} // namespace ble_remote
} // namespace esphome