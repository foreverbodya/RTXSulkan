#ifndef SULKAN_INTEGER_WRAP
#define SULKAN_INTEGER_WRAP
// Positive period, full signed 32-bit input. No negative operand reaches %.
int sulkanWrapInt(int value,int period) {
    uint magnitude=value<0?uint(-(value+1))+1u:uint(value);
    uint remainder=magnitude%uint(period);
    return value<0 && remainder!=0u?period-int(remainder):int(remainder);
}
#endif
