TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ACTIVESPM_DIR := $(abspath $(TEST_DIR)/../..)
CHIPYARD_ROOT := $(abspath $(ACTIVESPM_DIR)/../..)
GEMMINI_TESTS := $(CHIPYARD_ROOT)/generators/gemmini/software/gemmini-rocc-tests
BENCH_COMMON := $(GEMMINI_TESTS)/riscv-tests-benchmarks-common
GEMMINI_PARAMS := $(GEMMINI_TESTS)/include/gemmini_params.h

RISCV_PREFIX ?= riscv64-unknown-elf-
CC := $(RISCV_PREFIX)gcc
NM := $(RISCV_PREFIX)nm

BUILD_DIR := $(TEST_DIR)/build
TARGET := $(BUILD_DIR)/dual-gemmini-mesh-baremetal
SOURCE := $(TEST_DIR)/dual_gemmini_mesh.c

CFLAGS := \
	-march=rv64gc -Wa,-march=rv64gc \
	-mcmodel=medany -std=gnu99 -O2 \
	-ffast-math -fno-common -fno-builtin-printf \
	-fno-tree-loop-distribute-patterns \
	-DPREALLOCATE=1 -DMULTITHREAD=1 -DBAREMETAL=1 -DNUM_CORES=2 \
	-I$(ACTIVESPM_DIR)/software/include \
	-I$(GEMMINI_TESTS) \
	-I$(GEMMINI_TESTS)/riscv-tests \
	-I$(GEMMINI_TESTS)/riscv-tests/env \
	-I$(BENCH_COMMON)

LDFLAGS := \
	-nostdlib -nostartfiles -static \
	-T$(BENCH_COMMON)/test.ld

.PHONY: all clean check-header check-multihart

all: check-multihart

check-header:
	@test -f $(GEMMINI_PARAMS) || { \
		echo "Missing $(GEMMINI_PARAMS)"; \
		echo "Run the ActiveSPMDualGemminiMeshRocketConfig firrtl target first."; \
		exit 1; \
	}

$(BUILD_DIR):
	mkdir -p $@

$(TARGET): check-header $(SOURCE) $(ACTIVESPM_DIR)/software/include/activespm.h | $(BUILD_DIR)
	$(CC) $(CFLAGS) $(LDFLAGS) \
		$(SOURCE) $(wildcard $(BENCH_COMMON)/*.c) $(wildcard $(BENCH_COMMON)/*.S) \
		-lm -lgcc -o $@

check-multihart: $(TARGET)
	@$(NM) $(TARGET) | grep -q ' T thread_entry$$' || { \
		echo "ELF does not contain the dual-hart thread_entry override"; \
		exit 1; \
	}
	@echo "Built $(TARGET) with NUM_CORES=2"

clean:
	rm -rf $(BUILD_DIR)
