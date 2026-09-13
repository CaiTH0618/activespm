#ifndef ACTIVESPM_H
#define ACTIVESPM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ACTIVESPM_COMMAND_OFFSET UINT64_C(0x00)
#define ACTIVESPM_EXTERNAL_ADDR_OFFSET UINT64_C(0x08)
#define ACTIVESPM_LOCAL_OFFSET_OFFSET UINT64_C(0x10)
#define ACTIVESPM_BYTE_COUNT_OFFSET UINT64_C(0x18)
#define ACTIVESPM_STATUS_OFFSET UINT64_C(0x20)
#define ACTIVESPM_BYTES_COMPLETED_OFFSET UINT64_C(0x28)
#define ACTIVESPM_ERROR_CODE_OFFSET UINT64_C(0x30)

#define ACTIVESPM_COMMAND_START (UINT64_C(1) << 0)
#define ACTIVESPM_COMMAND_DIRECTION (UINT64_C(1) << 1)

#define ACTIVESPM_STATUS_BUSY (UINT64_C(1) << 0)
#define ACTIVESPM_STATUS_DONE (UINT64_C(1) << 1)
#define ACTIVESPM_STATUS_ERROR (UINT64_C(1) << 2)
#define ACTIVESPM_STATUS_W1C_MASK \
  (ACTIVESPM_STATUS_DONE | ACTIVESPM_STATUS_ERROR)

typedef enum {
  ACTIVESPM_DIRECTION_LOAD = 0,
  ACTIVESPM_DIRECTION_STORE = 1,
} activespm_direction_t;

typedef enum {
  ACTIVESPM_ERROR_NONE = 0,
  ACTIVESPM_ERROR_BUSY = 1,
  ACTIVESPM_ERROR_LOCAL_RANGE = 2,
  ACTIVESPM_ERROR_ADDRESS_OVERFLOW = 3,
  ACTIVESPM_ERROR_EXTERNAL_RANGE = 4,
  ACTIVESPM_ERROR_TILELINK = 5,
} activespm_error_t;

static inline volatile uint64_t *activespm_register(uintptr_t control_base,
                                                    uintptr_t offset) {
  return (volatile uint64_t *)(control_base + offset);
}

static inline uint64_t activespm_read64(uintptr_t control_base,
                                        uintptr_t offset) {
  return *activespm_register(control_base, offset);
}

static inline void activespm_write64(uintptr_t control_base, uintptr_t offset,
                                     uint64_t value) {
  *activespm_register(control_base, offset) = value;
}

static inline uint64_t activespm_read_status(uintptr_t control_base) {
  return activespm_read64(control_base, ACTIVESPM_STATUS_OFFSET);
}

static inline uint64_t activespm_read_bytes_completed(uintptr_t control_base) {
  return activespm_read64(control_base, ACTIVESPM_BYTES_COMPLETED_OFFSET);
}

static inline activespm_error_t activespm_read_error(uintptr_t control_base) {
  return (activespm_error_t)activespm_read64(control_base,
                                             ACTIVESPM_ERROR_CODE_OFFSET);
}

static inline void activespm_clear_status(uintptr_t control_base,
                                          uint64_t status_mask) {
  activespm_write64(control_base, ACTIVESPM_STATUS_OFFSET,
                    status_mask & ACTIVESPM_STATUS_W1C_MASK);
}

static inline void activespm_start(uintptr_t control_base,
                                   activespm_direction_t direction,
                                   uint64_t external_address,
                                   uint64_t local_offset,
                                   uint64_t byte_count) {
  activespm_write64(control_base, ACTIVESPM_EXTERNAL_ADDR_OFFSET,
                    external_address);
  activespm_write64(control_base, ACTIVESPM_LOCAL_OFFSET_OFFSET, local_offset);
  activespm_write64(control_base, ACTIVESPM_BYTE_COUNT_OFFSET, byte_count);
  __asm__ volatile("fence rw, rw" ::: "memory");
  activespm_write64(control_base, ACTIVESPM_COMMAND_OFFSET,
                    ACTIVESPM_COMMAND_START |
                        (direction == ACTIVESPM_DIRECTION_STORE
                             ? ACTIVESPM_COMMAND_DIRECTION
                             : UINT64_C(0)));
}

#ifdef __cplusplus
}
#endif

#endif
