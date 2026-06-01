const os = require('os');

const interfaces = os.networkInterfaces();
let localIp = null;

for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
        // Skip over non-IPv4 and internal (i.e. 127.0.0.1) addresses
        if (iface.family === 'IPv4' && !iface.internal) {
            localIp = iface.address;
            // Prefer 192.168.x.x addresses if multiple are found
            if (localIp.startsWith('192.168.')) {
                break;
            }
        }
    }
    if (localIp && localIp.startsWith('192.168.')) break;
}

if (localIp) {
    console.log('\n=========================================');
    console.log(`Your Local IP Address is: ${localIp}`);
    console.log(`Update syncUrl in SyncManager.kt to:`);
    console.log(`http://${localIp}:4000/sync`);
    console.log('=========================================\n');
} else {
    console.log('Could not find a local IPv4 address.');
}
