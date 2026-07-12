# Build the bare-metal OPERA DSP + Ethernet FPGA application.

PREFIX  ?= riscv64-unknown-elf-
CC      := $(PREFIX)gcc
OBJDUMP := $(PREFIX)objdump

ARCH    := rv64imafd
ABI     := lp64d
CMODEL  := medany

CFLAGS  := -std=c11 -Os -Wall -Wextra -fno-common -fno-builtin-printf \
           -ffunction-sections -fdata-sections \
           -march=$(ARCH) -mabi=$(ABI) -mcmodel=$(CMODEL) -specs=htif_nano.specs \
           -I. -I../ethernet -I../.. $(EXTRA_CFLAGS)
LDFLAGS := -static -T htif.ld -Wl,--gc-sections

TARGETS := opera_dsp_fpga.riscv
HDRS    := ../ethernet/eth.h ../ethernet/mdio.h ../ethernet/protocol.h \
           ../ethernet/rtl8211e.h mmio_config.h opera_dsp_regs.h

all: $(TARGETS)

opera_dsp_fpga.riscv: opera_dsp_fpga.c ../ethernet/protocol.c $(HDRS) htif.ld
	$(CC) $(CFLAGS) $(filter %.c,$^) $(LDFLAGS) -o $@

dump: $(TARGETS)
	$(foreach t,$(TARGETS),$(OBJDUMP) -D $(t) > $(t:.riscv=.dump);)

clean:
	rm -f $(TARGETS) $(TARGETS:.riscv=.dump)

.PHONY: all dump clean
