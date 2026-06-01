WaveMe 

Offline peer-to-peer messaging over BLE and WiFi Direct no SIM, no internet, no infrastructure needed.

WaveMe lets you communicate with people nearby without any internet connection, mobile data, or even a WiFi router. It works entirely over Bluetooth Low Energy (BLE) and WiFi Direct, connecting you directly with peers around you.

How it works
WaveMe uses two core transport layers:

BLE (Bluetooth Low Energy) — for peer discovery and short-range messaging
WiFi Direct — for faster, higher-bandwidth communication once peers are discovered

Messages travel using store-and-forward (hop) routing — if your target peer isn't directly reachable, the message hops through intermediate peers until it arrives. No central server. No routing table in the cloud. Just devices talking to devices.

Features

💬 Offline messaging — send and receive messages with zero internet
📡 BLE peer discovery — automatically finds nearby WaveMe users
⚡ WiFi Direct transport — fast data transfer once connected
🔁 Hop routing — messages relay through nearby peers to reach further devices
🔒 No SIM required — works even without a phone number or data plan
📱 Android native — built in Kotlin, optimised for Android


Tech Stack
LayerTechnologyLanguageKotlinTransportBLE (Bluetooth Low Energy), WiFi DirectMessagingP2P with store-and-forward hop routingNetworkingNo internet — LAN/proximity onlyPlatformAndroid

Use Cases

Communication during internet outages or network failures
Remote areas with no connectivity infrastructure
Disaster relief and emergency communication
Privacy-first messaging with no servers involved
Festivals, events, or crowded areas where networks are congested


Architecture
[Device A] ──BLE Discovery──► [Device B] ──WiFi Direct──► [Device C]
                                    │
                               Hop Routing
                                    │
                              [Device D] ──► [Device E]
Peers discover each other via BLE advertisements. Once discovered, WaveMe establishes a WiFi Direct connection for data transfer. If a destination peer is out of direct range, messages are relayed hop-by-hop through the mesh.

Getting Started
Prerequisites

Android 8.0 (Oreo) or above
Bluetooth and Location permissions enabled
WiFi enabled (no internet required)

Developer
Isha Bisht — Independent Android Developer, India
ishab301@gmail.com
