# IR code database

`app/src/main/assets/ir_tv.json.gz` is generated from the TV folder of
[Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) (CC0). Parsed signals are
pre-encoded to raw mark/space timings with the Flipper Zero firmware's own IR encoders
(`lib/infrared/encoder_decoder`, built on the host with the stubs here), so the app only replays timings.

    gcc -Istub -I$FW/lib/infrared/encoder_decoder -I$FW/lib/infrared/encoder_decoder/common irenc_main.c \
        $FW/lib/infrared/encoder_decoder/infrared.c $FW/lib/infrared/encoder_decoder/common/*.c \
        $FW/lib/infrared/encoder_decoder/{nec,samsung,sirc,rc5,rc6,kaseikyo,rca,pioneer}/infrared_*.c -o /tmp/irc/irenc
    python3 build_db.py ../../app/src/main/assets
