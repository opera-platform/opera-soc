CC       ?= cc
CPPFLAGS ?= -I.. -I../../ethernet
CFLAGS   ?= -std=c11 -Os -Wall -Wextra
LDLIBS   ?= -lm

TARGETS := opera_dsp_pc

all: $(TARGETS)

opera_dsp_pc: opera_dsp_pc.c ../../ethernet/protocol.c ../../ethernet/protocol.h
	$(CC) $(CPPFLAGS) $(CFLAGS) opera_dsp_pc.c ../../ethernet/protocol.c $(LDLIBS) -o $@

clean:
	rm -f $(TARGETS)

.PHONY: all clean
