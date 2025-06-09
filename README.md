# Vibe Monitor

## Introduction
New, very capable Android smartphones are now being sold for dirt cheap($30) by prepaid wireless companies that want you to buy their overpriced LTE data/SMS plan and use the phone like a normal person would.

Don't do that.

Instead, use the phone like you would a Raspberry Pi or ESP32 with a bunch of integrated sensors attached. 

Vibe Monitor is one such example program using it for the use case of wanting to detect when an appliance e.g. washing machine, dryer, dishwasher, etc. is finished with its cycle and get notified and track these cycles.

With the help of some double-sided adhesive and a spring-loaded selfie phone mount, you can attach the phone to pretty much anything and have Vibe Monitor setup to send a notification whenever a vibration over a certain threshold that you set is met or exceeded for a period of time you also can adjust: my personal use case right now is for getting notifications when the dryer is done because my dryer is older and doesn't have this built-in.



## Hardware Requirements
* Cheap Android phone
  * Moto G Play 2024 from Straight Talk Wireless ($30 new on walmart.com)
* Double-sided adhesive (3M VHB is good)
* Spring-loaded phone holder harvested from a selfie stick.


## Setup
Install the app and add the Webhook URL(s) you want it to send you a notification for. Could be Home Assistant, some custom server you have, Zapier, etc. and you could have the server-side send email or SMS depending on what you want.


## Goal
I plan on making this more generic and allow it to use  more of the built-in sensors besides the accelerometer of the phone for detecting all sorts of different things just from a phone. Ultimately like an ESPHome but for cheap Android smartphones.
