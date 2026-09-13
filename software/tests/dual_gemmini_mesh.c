#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "activespm.h"
#include "include/gemmini.h"
#include "include/gemmini_testutils.h"
#include "util.h"

#ifndef NUM_CORES
#error "dual_gemmini_mesh requires NUM_CORES"
#endif

#if NUM_CORES != 2
#error "dual_gemmini_mesh requires exactly two harts"
#endif

#define ACTIVE_SPM_CONTROL_BASE(hart) \
  (UINT64_C(0x10050000) + UINT64_C(0x1000) * (hart))
#define ACTIVE_SPM_SCRATCHPAD_BASE(hart) \
  (UINT64_C(0x70000000) + UINT64_C(0x10000) * (hart))

#define LOCAL_INPUT_OFFSET UINT64_C(0x0000)
#define LOCAL_OUTPUT_OFFSET UINT64_C(0x1000)
#define GUARD_BYTES 64
#define TRANSFER_ELEMENTS (DIM * DIM)
#define TRANSFER_BYTES (TRANSFER_ELEMENTS * sizeof(elem_t))
#define DMA_TIMEOUT_CYCLES UINT64_C(10000000)

typedef struct __attribute__((aligned(64))) {
  uint8_t leading_guard[GUARD_BYTES];
  elem_t data[TRANSFER_ELEMENTS];
  uint8_t trailing_guard[GUARD_BYTES];
} guarded_buffer_t;

static guarded_buffer_t inputs[NUM_CORES];
static guarded_buffer_t outputs[NUM_CORES];
static volatile uint64_t failure_mask;

enum {
  FAILURE_BAD_HART_COUNT = UINT64_C(1) << 0,
  FAILURE_LOAD = UINT64_C(1) << 1,
  FAILURE_LOAD_DATA = UINT64_C(1) << 2,
  FAILURE_GEMMINI_DATA = UINT64_C(1) << 3,
  FAILURE_STORE = UINT64_C(1) << 4,
  FAILURE_OUTPUT_DATA = UINT64_C(1) << 5,
  FAILURE_GUARD = UINT64_C(1) << 6,
  FAILURE_W1C = UINT64_C(1) << 7,
};

static uint8_t pattern_byte(int cid, size_t index) {
  return (uint8_t)(0x31u + (uint32_t)cid * 0x49u +
                   (uint32_t)index * 0x1du);
}

static void record_failure(int cid, uint64_t reason) {
  __sync_fetch_and_or(&failure_mask, reason << (cid * 8));
}

static bool any_failure(void) {
  __sync_synchronize();
  return failure_mask != 0;
}

static void initialize_buffers(int cid) {
  guarded_buffer_t *input = &inputs[cid];
  guarded_buffer_t *output = &outputs[cid];

  for (size_t i = 0; i < GUARD_BYTES; ++i) {
    input->leading_guard[i] = (uint8_t)(0xa0u + cid);
    input->trailing_guard[i] = (uint8_t)(0xb0u + cid);
    output->leading_guard[i] = (uint8_t)(0xc0u + cid);
    output->trailing_guard[i] = (uint8_t)(0xd0u + cid);
  }
  for (size_t i = 0; i < TRANSFER_ELEMENTS; ++i) {
    input->data[i] = (elem_t)pattern_byte(cid, i);
    output->data[i] = (elem_t)0;
  }
}

static bool check_guards(int cid) {
  const guarded_buffer_t *input = &inputs[cid];
  const guarded_buffer_t *output = &outputs[cid];

  for (size_t i = 0; i < GUARD_BYTES; ++i) {
    if (input->leading_guard[i] != (uint8_t)(0xa0u + cid) ||
        input->trailing_guard[i] != (uint8_t)(0xb0u + cid) ||
        output->leading_guard[i] != (uint8_t)(0xc0u + cid) ||
        output->trailing_guard[i] != (uint8_t)(0xd0u + cid)) {
      return false;
    }
  }
  return true;
}

static bool compare_bytes(const volatile elem_t *actual, int cid) {
  for (size_t i = 0; i < TRANSFER_ELEMENTS; ++i) {
    if ((uint8_t)actual[i] != pattern_byte(cid, i)) {
      return false;
    }
  }
  return true;
}

static bool wait_for_dma(uintptr_t control_base) {
  const uint64_t start = read_cycles();
  uint64_t status;

  do {
    status = activespm_read_status(control_base);
    if ((status & ACTIVESPM_STATUS_BUSY) == 0) {
      break;
    }
  } while (read_cycles() - start < DMA_TIMEOUT_CYCLES);

  if ((status & ACTIVESPM_STATUS_BUSY) != 0 ||
      (status & ACTIVESPM_STATUS_DONE) == 0 ||
      (status & ACTIVESPM_STATUS_ERROR) != 0 ||
      activespm_read_error(control_base) != ACTIVESPM_ERROR_NONE ||
      activespm_read_bytes_completed(control_base) != TRANSFER_BYTES) {
    return false;
  }

  __asm__ volatile("fence rw, rw" ::: "memory");
  return true;
}

static void run_hart(int cid, int nc) {
  const uintptr_t control_base = ACTIVE_SPM_CONTROL_BASE(cid);
  const uintptr_t scratchpad_base = ACTIVE_SPM_SCRATCHPAD_BASE(cid);
  volatile elem_t *scratchpad_input =
      (volatile elem_t *)(scratchpad_base + LOCAL_INPUT_OFFSET);
  volatile elem_t *scratchpad_output =
      (volatile elem_t *)(scratchpad_base + LOCAL_OUTPUT_OFFSET);

  initialize_buffers(cid);
  __asm__ volatile("fence rw, rw" ::: "memory");
  barrier(nc);

  activespm_start(control_base, ACTIVESPM_DIRECTION_LOAD,
                  (uintptr_t)inputs[cid].data, LOCAL_INPUT_OFFSET,
                  TRANSFER_BYTES);
  if (!wait_for_dma(control_base)) {
    record_failure(cid, FAILURE_LOAD);
  } else if (!compare_bytes(scratchpad_input, cid)) {
    record_failure(cid, FAILURE_LOAD_DATA);
  }
  barrier(nc);

  if (!any_failure()) {
    gemmini_flush(0);
    gemmini_config_ld(DIM * sizeof(elem_t));
    gemmini_config_st(DIM * sizeof(elem_t));
    barrier(nc);
    gemmini_extended_mvin(scratchpad_base + LOCAL_INPUT_OFFSET, 0, DIM, DIM);
    gemmini_fence();
    gemmini_extended_mvout(scratchpad_base + LOCAL_OUTPUT_OFFSET, 0, DIM, DIM);
    gemmini_fence();
    __asm__ volatile("fence rw, rw" ::: "memory");
    if (!compare_bytes(scratchpad_output, cid)) {
      record_failure(cid, FAILURE_GEMMINI_DATA);
    }
  }
  barrier(nc);

  if (!any_failure()) {
    activespm_start(control_base, ACTIVESPM_DIRECTION_STORE,
                    (uintptr_t)outputs[cid].data, LOCAL_OUTPUT_OFFSET,
                    TRANSFER_BYTES);
    if (!wait_for_dma(control_base)) {
      record_failure(cid, FAILURE_STORE);
    }
  }
  barrier(nc);

  if (!any_failure() && !compare_bytes(outputs[cid].data, cid)) {
    record_failure(cid, FAILURE_OUTPUT_DATA);
  }
  if (!check_guards(cid)) {
    record_failure(cid, FAILURE_GUARD);
  }

  activespm_clear_status(control_base,
                         ACTIVESPM_STATUS_DONE | ACTIVESPM_STATUS_ERROR);
  __asm__ volatile("fence rw, rw" ::: "memory");
  if ((activespm_read_status(control_base) & ACTIVESPM_STATUS_W1C_MASK) != 0 ||
      activespm_read_error(control_base) != ACTIVESPM_ERROR_NONE) {
    record_failure(cid, FAILURE_W1C);
  }
  barrier(nc);
}

void thread_entry(int cid, int nc) {
  if (nc != NUM_CORES) {
    if (cid == 0) {
      failure_mask = FAILURE_BAD_HART_COUNT;
      printf("ActiveSPM dual-instance test expected %d harts, got %d\n",
             NUM_CORES, nc);
      exit(1);
    }
    while (1) {
    }
  }

  run_hart(cid, nc);
  if (cid == 0) {
    const uint64_t failures = failure_mask;
    if (failures == 0) {
      printf("ActiveSPM dual Gemmini mesh test PASSED\n");
      exit(0);
    }
    printf("ActiveSPM dual Gemmini mesh test FAILED: 0x%lx\n", failures);
    exit(1);
  }

  while (1) {
  }
}

int main(void) {
  return 1;
}
