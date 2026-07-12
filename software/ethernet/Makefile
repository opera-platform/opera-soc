# Build the bare-metal Ethernet applications for RocketNexysVideoVerilogEthConfig.
#
#   make            # build all FPGA-side Ethernet binaries
#   make dump       # disassembly
#   make clean

PREFIX  ?= riscv64-unknown-elf-
CC      := $(PREFIX)gcc
OBJDUMP := $(PREFIX)objdump

ARCH    := rv64imafd_zifencei
ABI     := lp64d
CMODEL  := medany

CFLAGS   = -std=c11 -Os -Wall -Wextra -fno-common -fno-builtin-printf \
           -ffunction-sections -fdata-sections \
           -march=$(ARCH) -mabi=$(ABI) -mcmodel=$(CMODEL) -specs=htif_nano.specs \
           -I../.. $(EXTRA_CFLAGS)
LDFLAGS := -static -T htif.ld -Wl,--gc-sections

TARGETS := ethernet_fpga.riscv ethernet_sim_loopback.riscv \
           ethernet_phy_loopback.riscv ethernet_phy_loopback_1g.riscv
HDRS    := mmio_config.h eth.h mdio.h rtl8211e.h protocol.h

all: $(TARGETS)

ethernet_fpga.riscv:        ethernet_fpga.c protocol.c $(HDRS) htif.ld
ethernet_sim_loopback.riscv: ethernet_sim_loopback.c $(HDRS) htif.ld
ethernet_phy_loopback.riscv: ethernet_phy_loopback.c $(HDRS) htif.ld
ethernet_phy_loopback_1g.riscv: EXTRA_CFLAGS += -DPHY_LOOPBACK_SPEED_MBPS=1000
ethernet_phy_loopback_1g.riscv: ethernet_phy_loopback.c $(HDRS) htif.ld

$(TARGETS):
	$(CC) $(CFLAGS) $(filter %.c,$^) $(LDFLAGS) -o $@

dump: $(TARGETS)
	$(foreach t,$(TARGETS),$(OBJDUMP) -D $(t) > $(t:.riscv=.dump);)

clean:
	rm -f $(TARGETS) $(TARGETS:.riscv=.dump)

.PHONY: all dump clean
