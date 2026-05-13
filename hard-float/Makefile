SHELL := /usr/bin/env bash
.SHELLFLAGS := --noprofile --norc -eu -o pipefail -c

.SUFFIXES:
.NOTPARALLEL:

REPO_ROOT := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
PLAYGROUND_ROOT := $(abspath $(REPO_ROOT)..)/

TEST_OUTPUT_DIR ?= $(PLAYGROUND_ROOT)test-output

SOFTFLOAT_DIR := $(REPO_ROOT)berkeley-softfloat-3
TESTFLOAT_DIR := $(REPO_ROOT)berkeley-testfloat-3
TESTFLOAT_BIN_DIR := $(TESTFLOAT_DIR)/build/Linux-x86_64-GCC
SOFTFLOAT_LIB := $(SOFTFLOAT_DIR)/build/Linux-x86_64-GCC/softfloat.a
TESTFLOAT_GEN := $(TESTFLOAT_BIN_DIR)/testfloat_gen

MILL_JOBS ?= $(shell n="$$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1)"; [ "$$n" -gt 4 ] && echo 4 || echo "$$n")
MILL ?= $(PLAYGROUND_ROOT)mill
MILL_FLAGS ?= --no-daemon -j $(MILL_JOBS)

MILL_COMMAND := env PS1= "$(MILL)" $(MILL_FLAGS)
SCALAFMT_MODULES := {hardFloat,hardFloat.test}

HELP_OUTPUT_VARIABLES := TEST_OUTPUT_DIR
HELP_EXECUTION_VARIABLES := MILL_JOBS
HELP_TOOL_VARIABLES := MILL MILL_FLAGS

define PRINT_HELP_VALUES
@{ $(foreach var,$(1),printf '%s\t%s\n' "$(var)=" "$($(var))";) } | \
awk 'BEGIN { FS = "\t" } { v[++n] = $$1; d[n] = $$2; if (length($$1) > m) m = length($$1) } END { for (i=1; i<=n; i++) printf "  %-" m "s %s\n", v[i], d[i] }'
endef

.DEFAULT_GOAL := help

.PHONY: help testfloat check test lint format clean clean-all

help: ## Show targets and current defaults
	@echo "Usage:"
	@echo "  make <target> [VAR=value ...]"
	@echo ""
	@echo "Output:"
	$(call PRINT_HELP_VALUES,$(HELP_OUTPUT_VARIABLES))
	@echo ""
	@echo "Execution:"
	$(call PRINT_HELP_VALUES,$(HELP_EXECUTION_VARIABLES))
	@echo ""
	@echo "Tools:"
	$(call PRINT_HELP_VALUES,$(HELP_TOOL_VARIABLES))
	@echo ""
	@echo "Targets:"
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z0-9_.-]+:.*##/ { t[++n] = $$1; d[n] = $$2; if (length($$1) > m) m = length($$1) } END { for (i=1; i<=n; i++) printf "  %-" m "s %s\n", t[i], d[i] }' $(MAKEFILE_LIST)

testfloat: $(TESTFLOAT_GEN) ## Build Berkeley TestFloat generator

check: lint test ## Run formatting checks and hard-float tests

test: testfloat ## Run hard-float tests
	cd "$(PLAYGROUND_ROOT)" && TEST_OUTPUT_DIR="$(TEST_OUTPUT_DIR)" PATH="$(TESTFLOAT_BIN_DIR):$$PATH" $(MILL_COMMAND) hardFloat.test

lint: ## Check source formatting
	cd "$(PLAYGROUND_ROOT)" && $(MILL_COMMAND) "$(SCALAFMT_MODULES).checkFormat"

format: ## Format source files
	cd "$(PLAYGROUND_ROOT)" && $(MILL_COMMAND) "$(SCALAFMT_MODULES).reformat"

clean: ## Remove generated hard-float test files
	rm -rf -- "$(TEST_OUTPUT_DIR)/HardFloat"

clean-all: clean ## Remove generated hard-float test files and parent Mill state
	rm -rf -- "$(PLAYGROUND_ROOT)out"

$(SOFTFLOAT_DIR)/.git:
	git -C "$(PLAYGROUND_ROOT)" submodule update --init hard-float/berkeley-softfloat-3

$(TESTFLOAT_DIR)/.git:
	git -C "$(PLAYGROUND_ROOT)" submodule update --init hard-float/berkeley-testfloat-3

$(SOFTFLOAT_LIB): $(SOFTFLOAT_DIR)/.git
	$(MAKE) -C "$(SOFTFLOAT_DIR)/build/Linux-x86_64-GCC" SPECIALIZE_TYPE=RISCV

$(TESTFLOAT_GEN): $(TESTFLOAT_DIR)/.git $(SOFTFLOAT_LIB)
	$(MAKE) -C "$(TESTFLOAT_BIN_DIR)" SPECIALIZE_TYPE=RISCV
