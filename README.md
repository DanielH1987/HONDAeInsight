# HONDAeInsight
An Android App to read Real-Time-Data off of the Honda e and send them to the Interno API to have Real-Time-Data within ABRP.

This App is for testing purposes only! Feel free to add features, fix bugs and create pull requests.

The API-Key is not part of the repo.

You'll need an OBDII-Adapter that has at least 864 bytes message buffer with Bluetooth - not BLE!
A cheap ELM327-Clone won't work because of the length auf den CAN messages the e sends.

OBDII-Adapter known to be working are:
- OBDlink MX Bluetooth
- vLinker FD
- vLinker MC
- vLinker BM+
- vLinker BM

OBDII-Adapter not working:
- ELM327-Clone

Based heavily on https://github.com/harry1453/android-bluetooth-serial example App - Thanks!