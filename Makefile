SHELL := /usr/bin/env bash
.SHELLFLAGS := --noprofile --norc -eu -o pipefail -c

.SUFFIXES:
.NOTPARALLEL:

REPO_ROOT := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

CONFIG ?= $(REPO_ROOT)configs/default.json
TEST_OUTPUT_DIR ?= $(REPO_ROOT)test-output

HARD_FLOAT_DIR := $(REPO_ROOT)hard-float
SOFTFLOAT_SUBMODULE := hard-float/berkeley-softfloat-3
TESTFLOAT_SUBMODULE := hard-float/berkeley-testfloat-3
SOFTFLOAT_DIR := $(HARD_FLOAT_DIR)/berkeley-softfloat-3
TESTFLOAT_DIR := $(HARD_FLOAT_DIR)/berkeley-testfloat-3
TESTFLOAT_BIN_DIR := $(TESTFLOAT_DIR)/build/Linux-x86_64-GCC
SOFTFLOAT_LIB := $(SOFTFLOAT_DIR)/build/Linux-x86_64-GCC/softfloat.a
TESTFLOAT_GEN := $(TESTFLOAT_BIN_DIR)/testfloat_gen

MILL_JOBS ?= $(shell n="$$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1)"; [ "$$n" -gt 4 ] && echo 4 || echo "$$n")
MILL ?= $(REPO_ROOT)mill
MILL_FLAGS ?= --no-daemon -j $(MILL_JOBS)

MILL_COMMAND := env PS1= "$(MILL)" $(MILL_FLAGS)
APP_COMMAND := $(MILL_COMMAND) --ticker false playground.run
SCALAFMT_MODULES := {prefixTopologyLibrary,prefixTopologyLibrary.test,arithPrimitives,hardInt,hardInt.test,hardFloat,hardFloat.test,playground,playground.test}
TEST_MODULES := {prefixTopologyLibrary,hardInt,hardFloat,playground}.test

HELP_INPUT_VARIABLES := CONFIG
HELP_OUTPUT_VARIABLES := TEST_OUTPUT_DIR
HELP_EXECUTION_VARIABLES := MILL_JOBS
HELP_TOOL_VARIABLES := MILL MILL_FLAGS

define PRINT_HELP_VALUES
@{ $(foreach var,$(1),printf '%s\t%s\n' "$(var)=" "$($(var))";) } | \
awk 'BEGIN { FS = "\t" } { v[++n] = $$1; d[n] = $$2; if (length($$1) > m) m = length($$1) } END { for (i=1; i<=n; i++) printf "  %-" m "s %s\n", v[i], d[i] }'
endef

.DEFAULT_GOAL := help

.PHONY: help rtl prelayout check test testfloat lint format clean clean-all

help: ## Show targets and current defaults
	@echo "Usage:"
	@echo "  make <target> [VAR=value ...]"
	@echo ""
	@echo "Input:"
	$(call PRINT_HELP_VALUES,$(HELP_INPUT_VARIABLES))
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

rtl: ## Write wrapper SystemVerilog from CONFIG
	@$(APP_COMMAND) rtl "$(CONFIG)"

prelayout: ## Write wrapper SystemVerilog and prelayout report from CONFIG
	@$(APP_COMMAND) prelayout "$(CONFIG)"

check: lint test ## Run formatting checks and tests

test: testfloat ## Run tests
	TEST_OUTPUT_DIR="$(TEST_OUTPUT_DIR)" PATH="$(TESTFLOAT_BIN_DIR):$$PATH" $(MILL_COMMAND) "$(TEST_MODULES)"

testfloat: $(TESTFLOAT_GEN) ## Build hard-float test vector generator

lint: ## Check source formatting
	$(MILL_COMMAND) "$(SCALAFMT_MODULES).checkFormat"

format: ## Format source files
	$(MILL_COMMAND) "$(SCALAFMT_MODULES).reformat"

clean: ## Remove generated wrapper files
	rm -rf -- "$(REPO_ROOT)generated"

clean-all: clean ## Remove generated wrapper files and Mill state
	rm -rf -- "$(REPO_ROOT)out"

$(SOFTFLOAT_DIR)/.git:
	git submodule update --init "$(SOFTFLOAT_SUBMODULE)"

$(TESTFLOAT_DIR)/.git:
	git submodule update --init "$(TESTFLOAT_SUBMODULE)"

$(SOFTFLOAT_LIB): $(SOFTFLOAT_DIR)/.git
	$(MAKE) -C "$(SOFTFLOAT_DIR)/build/Linux-x86_64-GCC" SPECIALIZE_TYPE=RISCV

$(TESTFLOAT_GEN): $(TESTFLOAT_DIR)/.git $(SOFTFLOAT_LIB)
	$(MAKE) -C "$(TESTFLOAT_BIN_DIR)" SPECIALIZE_TYPE=RISCV
