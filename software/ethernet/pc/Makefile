CC       ?= cc
CPPFLAGS ?= -I../../..
CFLAGS   ?= -std=c11 -Os -Wall -Wextra

TARGET := ethernet_pc
SRCS   := ethernet_pc.c ../protocol.c
HDRS   := ../protocol.h

all: $(TARGET)

$(TARGET): $(SRCS) $(HDRS)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(SRCS) -o $@

clean:
	rm -f $(TARGET)

.PHONY: all clean
