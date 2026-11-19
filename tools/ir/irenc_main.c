#include "infrared.h"
#include <stdio.h>
#include <string.h>
int main(){
  char proto[32]; unsigned a,c;
  InfraredEncoderHandler* h = infrared_alloc_encoder();
  while (scanf("%31s %x %x",proto,&a,&c)==3){
    InfraredProtocol p = infrared_get_protocol_by_name(proto);
    if (p==InfraredProtocolUnknown){ printf("ERR\n"); continue; }
    InfraredMessage m = {.protocol=p,.address=a,.command=c,.repeat=false};
    infrared_reset_encoder(h,&m);
    size_t reps = infrared_get_protocol_min_repeat_count(p); if (reps<1) reps=1;
    printf("%u", infrared_get_protocol_frequency(p));
    size_t done=0; int guard=0;
    while (done<reps && guard++<5000){
      uint32_t d; bool lvl; InfraredStatus s = infrared_encode(h,&d,&lvl);
      if (s==InfraredStatusError){ printf(" E"); break; }
      printf(" %c%u", lvl?'+':'-', d);
      if (s==InfraredStatusDone) done++;
    }
    printf("\n"); fflush(stdout);
  }
}
