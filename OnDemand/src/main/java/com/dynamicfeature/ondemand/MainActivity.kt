package com.dynamicfeature.ondemand

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.play.core.splitcompat.SplitCompat
import java.io.*
import java.util.*
import java.util.zip.CRC32
import com.dynamicfeature.ondemand.BLELinkMessage.*

class MainActivity : AppCompatActivity() {

    // THE UUID OF THE AIM HEADBAND WHICH IS THE BLUETOOTH ID
    // THE UUID SHOULD BE THE SAME FOR *ALL AIM HEADBANDS* BECAUSE IT'S THE FILTERING CRITERIA FOR BLUETOOTH SCANNING
    private val AIM_HEADBAND_UUID: UUID = UUID.fromString("2559c4c5-aff1-47de-8c4f-af465b74ef86")

    // CONFIGURATION CONSTANTS
    // GOOGLE THE VARIABLE NAME TO READ DOCUMENTATIONS FOR FURTHER INFORMATION
    private val ENABLE_BLUETOOTH_REQUEST_CODE = 1
    private val LOCATION_PERMISSION_REQUEST_CODE = 2
    private val WRITE_PERMISSION_REQUEST_CODE = 3
    private val BLUETOOTH_WRITE_TYPE = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private val GATT_MAX_MTU_SIZE = 517

    // CHAQUOPY
    private lateinit var pyObject: PyObject

    private lateinit var scan_result_recycler_view : RecyclerView
    // GLOBAL VARIABLE TO THE BOOLEAN INDICATING WHETHER THE COMMUNICATION LINK IS FREE OR BUSY
    private var link_status = true // TRUE = FREE - FALSE = BUSY
        set(value) {
            field = value
            if(value)
                handleLink()
        }
    private var img_packet_num = false
    private var img_pack = 0

    // GLOBAL VARIABLE TO THE NUMBER OF ERRORS OCCURRED DURING CURRENT TRANSMISSION
    private var error_count = 0

    // GLOBAL ARRAYS OF TYPE "BYTEARRAY" TO STORE THE IMAGE, EEG, MPU AND PPG DATA
    private var pic_array = mutableListOf<ByteArray>()

    // A TAG ASSOCIATED WITH ALL PRINTED MESSAGES ON THE LOG
    private val TAG = "tag"

    //  GLOABL VARIABLE TO INDICATE WHETHER BLE SCANNING IS ON/OFF
    private var isScanning = false

    private var packet_img = 0

    // GLOBAL VARIABLE TO THE DOWNLOAD DIRECTORY
    private var DOWNLOAD_DIR: File? = null

    // THE UUID (BLUETOOTH ID) FOR THE SERVICES AND CHARACTERISTICS
    private val CNCT_ERR_SERV_UUID : UUID = UUID.fromString("675df09c-378f-498c-a73d-51e107bb152b")
    private val CNCT_ERR_CHAR_UUID : UUID = UUID.fromString("68c05225-74f2-420a-aa84-c5ce1174f685")
    private val EEG_CHAR_UUID : UUID = UUID.fromString("134af633-2230-4356-b698-93fbe7a66606")
    private val MPU_CHAR_UUID : UUID = UUID.fromString("f3812779-e8fd-495a-bdb8-5c39f6a6c553")
    private val PPG_CHAR_UUID : UUID = UUID.fromString("694310f1-c317-49a3-8465-4767b7c38c27")
    private val IMAGE_SERV_UUID : UUID = UUID.fromString("2559c4c5-aff1-47de-8c4f-af465b74ef86")
    private val IMAGE_CHAR_UUID : UUID = UUID.fromString("9fe148e5-d609-47cc-a0c4-8dd00da4cdce")

    private val isWritePermissionGranted
        get() = hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    // FUNCTION TO CHECK WHETHER PERMISSION IS GRANTED FOR ANY PERMISSION
    private fun Context.hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permissionType) ==
                PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_ondemand)

        // INITIALISATION OF VIEWS
        viewsInit()

        // CHAQUOPY INITIALISATION
        if (! Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val py = Python.getInstance()
        pyObject = py.getModule("yolov5ChaqA")

        // CHECK WRITE PERMISSION
        requestWritePermission()
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        SplitCompat.install(this)
    }

    override fun onResume() {
        super.onResume()
        if (!bluetoothAdapter.isEnabled) {
            promptEnableBluetooth()
        } else {
            startBleScan()
        }
    }

    private fun viewsInit() {
        scan_result_recycler_view = findViewById(R.id.scan_results_recycler_view)
    }

    // FUNCTION TO REQUEST WRITE PERMISSION
    // SAME LOGIC AS LOCATION
    private fun requestWritePermission() {
        if (isWritePermissionGranted) {
            return
        }
        val posClick = { dialog: DialogInterface, which: Int ->
            requestPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                WRITE_PERMISSION_REQUEST_CODE
            )
        }
        runOnUiThread {
            val alert = AlertDialog.Builder(this)
            alert.setTitle("Write permission required")
            alert.setMessage("Data logging requires writing permission.")
            alert.setCancelable(false)
            alert.setPositiveButton("OK", DialogInterface.OnClickListener(function = posClick))

            alert.show()
        }
    }

    private fun Activity.requestPermission(permission: String, requestCode: Int) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    // <------------------------------------- Bluetooth & Location Permission ------------------------------------->
    // Bluetooth Manager is the Bluetooth Module inside every android device
    // Bluetooth Adapter is the object responsible for handling the Bluetooth functionality
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // FUNCTION TO REQUEST USER TO TURN ON BLUETOOTH
    private fun promptEnableBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE)
        }
    }

    // OVERRIDE FUNCTION : onActivityResult
    // FUNCTION RESPONSIBLE FOR DEALING WITH THE ANSWER OF BLUETOOTH REQUEST
    // IF USER DOES ALLOW BLUETOOTH --> DO NOTHING
    // IF USER DONT ALLOW BLUETOOTH --> ASK SAME QUESTION AGAIN
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ENABLE_BLUETOOTH_REQUEST_CODE -> {
                if (resultCode != Activity.RESULT_OK)
                    promptEnableBluetooth()
                else
                    startBleScan()
            }
        }
    }

    // GLOBAL VARIABLE TO DETERMINE WHETHER THE ACTIVITY HAS PERMISSION TO GET LOCATION
    // THIS PART IS NECESSARY FOR BLUETOOTH TO FUNCTION
    // WITHOUT IT, BLUETOOTH WOULD BE TURNED ON BUT NO DEVICES WOULD BE SCANNED WITHOUT ANY ERROR MESSAGES
    // TODO : VALIDATE WHETHER NEW HUAWEI PHONES NEED USER TO ALLOW LOCATION MANUALLY
    private val isLocationPermissionGranted
        get() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)


    // FUNCTION TO REQUEST LOCATION PERMISSION
    private fun requestLocationPermission() {

        // IF PERMISSION IS GRANTED --> RETURN
        if (isLocationPermissionGranted) {
            return
        }

        // CREATE THE FUNCTION FOR THE POSITIVE CLICK
        // THIS FUNCTION TURN BLUETOOTH ON
        val posClick = { _: DialogInterface, _: Int ->
            requestPermission(
                Manifest.permission.ACCESS_FINE_LOCATION,
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        // DISPLAY AN ALERT TO REQUEST USER TO TURN BLUETOOTH ON
        // THIS IS DONE AS GOOD PRACTICE AND IS FOLLOWED BY MOST DEPLOYED APPLICATIONS
        runOnUiThread {
            val alert = AlertDialog.Builder(this)
            alert.setTitle("Location permission required")
            alert.setMessage("Starting from Android M (6.0), the system requires apps to be granted " +
                    "location access in order to scan for BLE devices.")
            alert.setCancelable(false)
            alert.setPositiveButton("OK", DialogInterface.OnClickListener(function = posClick))
            alert.show()
        }
    }

    // OVERRIDE FUNCTION : onRequestPermissionResult
    // FUNCTION RESPONSIBLE FOR DEALING WITH THE ANSWER OF LOCATION, READ AND WRITE REQUESTS
    // IF USER DOES ALLOW LOCATION --> DO NOTHING
    // IF USER DONT ALLOW LOCATION --> ASK SAME QUESTION AGAIN
    // IF USER DOES ALLOW READ --> DO NOTHING
    // IF USER DONT ALLOW READ --> ASK SAME QUESTION AGAIN
    // IF USER DOES ALLOW WRITE --> DO NOTHING
    // IF USER DONT ALLOW WRITE --> ASK SAME QUESTION AGAIN
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestLocationPermission()
                }
            }
            WRITE_PERMISSION_REQUEST_CODE ->{
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestWritePermission()
                }
            }
        }
    }

    // <------------------------------------- Start Scan ------------------------------------->
    // Bluetooth Adapter is the object responsible for handling the Bluetooth functionality
    // Bluetooth Scanner is the object responsible for scanning Bluetooth devices
    // by lazy --> The object will be initialised when it is called not upon startup
    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    // FUNCTION RESPONSIBLE FOR SCANNING BLUETOOTH DEVICES
    private fun startBleScan() {

        // CHECK IF DEVICE NEEDS LOCATION PERMISSION
        // DEVICE NEEDS LOCATION PERMISSION IF THE ANDROID VERSION IS MARSHMALLOW OR LATER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isLocationPermissionGranted) {
            requestLocationPermission()
        }

        // CLEAR SCAN RESULTS AND UPDATE RECYCLERVIEW
        scanResults.clear()
        scanResultAdapter.notifyDataSetChanged()

        // AI MNEMONIC FILTERED SCAN
        bleScanner.startScan(listOf(filter), scanSettings, scanCallback)

        // NO FILTER SCAN -- UNCOMMENT NEXT LINE IF NEEDED
        // bleScanner.startScan(null, scanSettings, scanCallback)

        // UPDATE GLOBAL VARIABLE INDICATING THE SCAN STATUS
        isScanning = true
    }

    // THIS FILTER ALLOWS THE APPLICATION TO SCAN AIM HEADBAND DEVICES ONLY
    private val filter: ScanFilter = ScanFilter.Builder().setServiceUuid(
        ParcelUuid.fromString(AIM_HEADBAND_UUID.toString())
    ).build()

    // THIS SCAN SETTING IS THE MOST OPTIMAL HIGH-POWER BLUETOOTH SCANNING MODE
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    // THIS OBJECT HOLDS REFERENCE TO BLUETOOTH SCAN MANAGER
    private val scanCallback = object : ScanCallback() {

        // OVERRIDE FUNCTION : onScanResult
        // UPON FINDING A SCAN RESULT
        override fun onScanResult(callbackType: Int, result: ScanResult) {

            // UPDATE RECYCLERVIEW TO DISPLAY SCAN RESULT
            val indexQuery = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (indexQuery != -1) { // A SCAN RESULT ALREADY EXISTS WITH THE SAME NAME
                scanResults[indexQuery] = result
                scanResultAdapter.notifyItemChanged(indexQuery)
            } else { // THE SCAN RESULT IS NEW
                with(result.device) {
                    Log.i(TAG, "Found BLE device! Name: ${name ?: "Unnamed"}, address: $address")
                }
                scanResults.add(result)
                scanResultAdapter.notifyItemInserted(scanResults.size - 1)
            }
        }
    }

    // FUNCTION RESPONSIBLE FOR STOP SCANNING
    private fun stopBleScan() {
        // THE FUNCTION RESPONSIBLE FOR STOPPING THE SCAN
        bleScanner.stopScan(scanCallback)

        // UPDATE GLOBAL VARIABLE INDICATING THE SCAN STATUS
        isScanning = false

        // CLEAR SCAN RESULTS AND UPDATE RECYCLERVIEW
        scanResults.clear()
        scanResultAdapter.notifyDataSetChanged()
    }

    // <------------------------------------- Display Scan ------------------------------------->

    // THIS FUNCTION IS RESPONSIBLE FOR SETTING UP THE RECYCLERVIEW
    private fun setupRecyclerView() {

        // THIS BLOCK IS INITIALISING THE ATTRIBUTES WITHIN THE RECYCLERVIEW
        scan_result_recycler_view.apply {

            // THIS IS EQUIVALENT TO --> scan_result_recycler_view.adapter = scanResultAdapter
            adapter = scanResultAdapter
            layoutManager = LinearLayoutManager(
                this@MainActivity,
                RecyclerView.VERTICAL,
                false
            )
            isNestedScrollingEnabled = false
        }

        val animator = scan_result_recycler_view.itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }
    }

    // THIS GLOBAL LIST HOLDS THE VALUES OF ALL THE SCANNED RESULTS
    private val scanResults = mutableListOf<ScanResult>()
    // THIS GLOBAL VARIABLE IS THE BLE SCANNING ADAPTER
    // BY LAZY MEANS THAT THE VARIABLE WILL BE INITIALISED DURING ITS FIRST CALL
    private val scanResultAdapter: ScanResultAdapter by lazy {
        // THE INITIALISED VALUE WILL BE THE RETURNED OBJECT FROM THE FOLLOWING BLOCK
        ScanResultAdapter(scanResults) { result ->
            // User tapped on a scan result
            if (isScanning) {
                stopBleScan()
            }
            with(result.device) {
                Log.w(TAG, "Connecting to $address")
                // THIS IS THE FUNCTION THAT CONNECTS THE SCANNED DEVICE
                connectGatt(this@MainActivity, true, gattCallback)
                // THIS IS A GLOBAL REFERENCE TO THE CONNECTED DEVICE
                deviceName = result.device.name
            }
        }
    }

    private lateinit var deviceName : String
    lateinit var bluetoothGatt: BluetoothGatt

    private var connection_status = false // TRUE = CONNECTED - FALSE = NOT CONNECTED

    // THIS IS THE MAIN COMMUNICATION OBJECT
    private val gattCallback = object : BluetoothGattCallback() {

        // OVERRIDE FUNCTION : onConnectionStateChange
        // THIS FUNCTION IS CALLED EVERYTIME THE STATE OF CONNECTION IS CHANGED
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            // IF CONNECTION COULD BE IDENTIFIED
            if (status == BluetoothGatt.GATT_SUCCESS) {

                // IF STATE IS CONNECTED
                if (newState == BluetoothProfile.STATE_CONNECTED) {

                    // UPDATE GLOBAL VARIABLE INDICATING THE STATE OF CONNECTION
                    connection_status = true
                    Log.w(TAG, "Successfully connected to $deviceAddress")

                    // UPDATE THE BLUETOOTH GATT RESPONSIBLE FOR COMMUNICATION
                    bluetoothGatt = gatt

                    // REQUEST THE MTU OF CONNECTION
                    gatt.requestMtu(GATT_MAX_MTU_SIZE)
                }

                // IF STATE IS DISCONNECTED (WHICH ONLY OCCURS WHEN YOU CLICK ON THE DISCONNECT BUTTON)
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                    // UPDATE GLOBAL VARIABLE INDICATING THE STATE OF CONNECTION
                    connection_status = false
                    Log.w(TAG, "Successfully disconnected from $deviceAddress")

                    // AS GOOD PRACTICE, GATT SHOULD BE CLOSED AS EARLY AS POSSIBLE FROM CODE
                    gatt.close()
                }
            }

            // IF CONNECTION COULD NOT BE IDENTIFIED (DISCONNECTED WITH ERROR)
            else {

                // UPDATE GLOBAL VARIABLE INDICATING THE STATE OF CONNECTION
                connection_status = false
                Log.w(TAG, "Error $status encountered for $deviceAddress! Disconnecting...")

                // THIS FUNCTION ATTEMPTS TO RECONNECT
                gatt.connect()
            }
        }

        // OVERRIDE FUNCTION : onMtuChanged
        // THIS FUNCTION IS CALLED WHEN MTU IS REQUESTED OR UPDATED
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // DISPLAY THE SIZE OF MTU
            Log.w(TAG, "ATT MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}")

            // TODO : PERFORM ERROR HANDLING FOR THE CASE THAT THE MTU OBTAINED IS LESS THAN 500

            // DISCOVER THE BLE SERVICES
            bluetoothGatt.discoverServices()
        }

        // OVERRIDE FUNCTION : onServicesDiscovered
        // THIS FUNCTION IS CALLED WHEN BLE SERVICES ARE DISCOVERED
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt) {
                Log.w(TAG, "Discovered ${services.size} services for ${device.address}")
                printGattTable()

                // THIS FUNCTION IS RESPONSIBLE FOR READING THE CNCT_ERR_CHAR CHARACTERISTIC WITHIN THE CNCT_ERR_SERV SERVICES
                // READ SEQUENCE DIAGRAM FOR MORE INFORMATION
                readCharacteristic(CNCT_ERR_SERV_UUID, CNCT_ERR_CHAR_UUID)
            }
        }

        // OVERRIDE FUNCTION : onCharacteristicRead
        // THIS FUNCTION IS CALLED WHEN BLE CHARACTERISTICS ARE READ FOR THE FIRST TIME
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {

                        // HANDLING OF READ CHARACTERISTIC BASED ON CHARACTERISTIC
                        when (characteristic.uuid) {
                            // ITERATE THROUGH THE READINGS
                            CNCT_ERR_CHAR_UUID -> {
                                Log.i(TAG, "Reading CNCT characteristic $uuid:\nString: ${String(value)}")
                                readCharacteristic(CNCT_ERR_SERV_UUID, EEG_CHAR_UUID)
                            }
                            EEG_CHAR_UUID -> {
                                Log.i(TAG, "Reading EEG characteristic $uuid:\nString: ${String(value)}")
                                readCharacteristic(CNCT_ERR_SERV_UUID, MPU_CHAR_UUID)
                            }
                            MPU_CHAR_UUID -> {
                                Log.i(TAG, "Reading MPU characteristic $uuid:\nString: ${String(value)}")
                                readCharacteristic(CNCT_ERR_SERV_UUID, PPG_CHAR_UUID)
                            }
                            PPG_CHAR_UUID -> {
                                Log.i(TAG, "Reading PPG characteristic $uuid:\nString: ${String(value)}")
                                readCharacteristic(IMAGE_SERV_UUID, IMAGE_CHAR_UUID)
                            }
                            // READING OF LAST CHARACTERISTIC
                            IMAGE_CHAR_UUID -> {
                                Log.i(TAG, "Reading Image characteristic $uuid:\nString: ${String(value)}")

                                // CONNECTION IS ESTABLISHED AT THIS POINT

                                // START FOCUS
                                addLinkQueue("Start Focus!")
                            }
                        }
                        // ALL CHARACTERISTICS SHARE SUBSCRIBTION FEATURE
                        bluetoothGatt.setCharacteristicNotification(characteristic, true)
                    }
                    // ERROR HANDLING
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        Log.e(TAG, "Read not permitted for $uuid!")
                    }
                    else -> {
                        Log.e(TAG, "Characteristic read failed for $uuid, error: $status")
                    }
                }
            }
        }

        // OVERRIDE FUNCTION : onCharacteristicChanged
        // THIS FUNCTION IS CALLED WHEN BLE CHARACTERISTICS ARE CHANGED
        // THIS IS THE MOST IMPORTANT COMMUNICATION FUNCTION
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            with(characteristic) {
                when (uuid) {
                    // RESPONSIBLE FOR ADMIN COMMUNICATIONS AND FUTURE ERROR HANDLING
                    CNCT_ERR_CHAR_UUID -> {
                        when {
                            // THIS IS A CONFIRMATION THAT FIRMWARE STARTED FOCUS
                            String(value) == "Focus Started!" -> {
                                // CREATE THE DOWNLOAD DIRECTORY
                                DOWNLOAD_DIR = getCustomDirectory()
                                // START COUNTING HOW MUCH TIME IT TAKES 3 DATA CYCLES
                                oldTime = getDataCycleSeconds()
                            } else -> {
                            Log.e(TAG, "Value received on CNCT Char is unknown! : ${String(value)}")
                        }
                        }
                    }
                    // RESPONSIBLE FOR RECEIVING IMAGE
                    IMAGE_CHAR_UUID -> {
                        when {
                            // IMAGE IS SENT SUCCESSFULLY
                            String(value) == "Image sent successfully" -> {
                                Log.i(TAG, "Image sent successfully!")
                                img_packet_num = true
                            }
                            // A PACKET CHUNK IS SENT SUCCESSFULLY (CHECK SEQUENCE DIAGRAM FOR DETAILS)
                            String(value) == "End of a complete packet chunk" -> {
                                Log.e(TAG, "Received a complete packet chunk")
                                writeCharacteristic("Send more Image".toByteArray())
                            }
                            // THE LAST PACKET CHUNK IS SENT SUCCESSFULLY
                            String(value) == "End of the remaining packet chunk" -> {
                                Log.i(TAG, "Image sent successfully!")
                                img_packet_num = true
                            }
                            // ERROR HANDLING FOR THE NUMBER OF PACKETS AND THE LAST STAGE OF IMAGE TRANSFER
                            img_packet_num -> {
                                img_packet_num = false
                                img_pack = value.getUInt42(0)
                                Log.e(TAG, "Number of Image packets : ${String(value)} --- img_pack : $img_pack")

                                // PROCESS THE IMAGE
                                process_image()
                                Log.e(TAG, "Number of Image packets : $img_pack")
                            }
                            else -> {
                                // THIS PART IS COMPLEX SO BETTER CHECK SEQUENCE DIAGRAM

                                // COMMUNICATION CHANNEL IS BUSY
                                link_status = false

                                // INCREMENT IMAGE PACKET COUNT
                                packet_img++

                                // STORE ADDED IMAGE DATA
                                pic_array.add(value)
                                Log.i(TAG, "Read characteristic $uuid:\n${value.toHexString()}")
                            }
                        }
                    }
                    // RESPONSIBLE FOR RECEIVING EEG DATA
                    EEG_CHAR_UUID -> {

                    }
                    // RESPONSIBLE FOR RECEIVING MPU DATA
                    MPU_CHAR_UUID -> {

                    }
                    // RESPONSIBLE FOR RECEIVING PPG DATA
                    PPG_CHAR_UUID -> {

                    }
                    // THERE IS NO WAY THE APPLICATION WOULD DETECT UNKNOWN CHARACTERISTIC UNLESS THE FIRMWARE CODE HAS BEEN TAMPERED WITH
                    else -> {
                        Log.e(TAG, "Error impossible to happen")
                    }
                }
            }
        }

        // OVERRIDE FUNCTION : onCharacteristicWrite
        // THIS FUNCTION IS CALLED WHEN BLE CHARACTERISTIC CNCT_ERR_CHAR IS BEING WRITTEN ON (SENDING TO FIRMWARE)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // THIS CHARACTERISTIC WILL ALWAYS BE THE ADMIN CHARACTERISTIC CNCT_ERR_CHAR
            with(characteristic) {
                when (status) {
                    // IF WRITING WAS A SUCCESS
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.e(TAG, "Wrote to characteristic $uuid | value: ${value.toHexString()}")
                    }
                    // IF WE ATTEMPTED WRITING A MESSAGE THAT'S TOO BIG
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                        Log.e(TAG, "Write exceeded connection ATT MTU!")
                    }
                    // IF WE ATTEMPTED WRITING TO A CHARACTERISTIC THAT'S READ ONLY (IMPOSSIBLE TO HAPPEN)
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Log.e(TAG, "Write not permitted for $uuid!")
                    }
                    // OTHER RANDOM ERRORS
                    else -> {
                        Log.e(TAG, "Characteristic write failed for $uuid, error: $status")
                    }
                }
            }
        }
    }

    // PRINT SERVICES INFORMATION TO LOG
    private fun BluetoothGatt.printGattTable() {
        if (services.isEmpty()) {
            Log.i(TAG,"No service and characteristic available, call discoverServices() first?")
            return
        }
        services.forEach { service ->
            val characteristicsTable = service.characteristics.joinToString(
                separator = "\n|--",
                prefix = "|--"
            ) { it.uuid.toString() }
            Log.i(TAG, "\nService ${service.uuid}\nCharacteristics:\n$characteristicsTable"
            )
        }
    }

    // CHECK WHETHER CHARACTERISTIC IS READABLE
    private fun BluetoothGattCharacteristic.isReadable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    // FUNCTION RESPONSIBLE FOR READING CHARACTERISTICS
    private fun readCharacteristic(SERVICE_UUID : UUID, CHAR_UUID : UUID) {
        val characteristic = bluetoothGatt.getService(SERVICE_UUID).getCharacteristic(CHAR_UUID)
        if(characteristic?.isReadable() == true) {
            bluetoothGatt.readCharacteristic(characteristic)
        }
    }

    // FUNCTION RESPONSIBLE FOR HANDLING THE DATA STRUCTURE RESPONSIBLE FOR HANDLING COMMUNICATION
    private fun handleLink() {
        if(link_status) {
            val msg = BLELinkMessage.handleLink()
            if(msg.equals("Do nothing!"))
                return
            writeCharacteristic(msg.toByteArray())
        }
    }
    // FUNCTION RESPONSIBLE FOR ADDING A MESSAGE TO BE SENT
    private fun addLinkQueue(msg : String) {
        val linkObject = BLELinkMessage(msg)
        if(addLinkQueue(linkObject))
            handleLink()
        else
            Log.d(TAG, "Can not add message to queue")
    }

    // CREATE DIRECTORY IN INTERNAL STORAGE
    private fun getCustomDirectory() : File {
        var count = 1
        var name = nowThereIsNoTimeAtAll()
        Log.e(TAG, name)
        val parent = filesDir
        var folder = File(parent, name)
        Log.e(TAG, "Creating folder $name")

        while(folder.exists()) {
            // Update count and create again
            count++
            name = "AIM Data $count"
            folder = File(parent, name)
            Log.e(TAG, "Creating folder $name")
        }

        // Create the directory
        try {
            folder.mkdirs()

            Log.e(TAG, "Folder ${folder.name} created successfully!")
            Log.e(TAG, "Path : ${folder.absolutePath}")
        } catch (e : SecurityException) {
            Log.w(TAG, e)
            Log.e(TAG, "Folder ${folder.name} failed!")
        }

        return folder
    }

    // CREATE THE TIMESTAMP AS THE NAME OF THE FOLDER
    private fun nowThereIsNoTimeAtAll() : String {
        val currentTime = Calendar.getInstance()

        val year = currentTime.get(Calendar.YEAR)
        val month_int = currentTime.get(Calendar.MONTH) + 1
        val day_int = currentTime.get(Calendar.DAY_OF_MONTH)
        val hour_int = currentTime.get(Calendar.HOUR_OF_DAY)
        val minute_int = currentTime.get(Calendar.MINUTE)
        val second_int = currentTime.get(Calendar.SECOND)

        var month : String = month_int.toString()
        if(month_int < 10)
            month = "0$month_int"

        var day : String = day_int.toString()
        if(day_int < 10)
            day = "0$day_int"

        var hour : String = hour_int.toString()
        if(hour_int < 10)
            hour = "0$hour_int"

        var minute : String = minute_int.toString()
        if(minute_int < 10)
            minute = "0$minute_int"

        var second : String = second_int.toString()
        if(second_int < 10)
            second = "0$second_int"

        return "${year%100}$month$day-$hour$minute$second"
    }

    // STOPWATCH TOOLS
    var oldTime = ""
    var newTime = ""
    var imgTime = ""
    // GET CURRENT TIME
    private fun getDataCycleSeconds() : String {
        val currentTime = Calendar.getInstance()

        val hour_int = currentTime.get(Calendar.HOUR_OF_DAY)
        val minute_int = currentTime.get(Calendar.MINUTE)
        val second_int = currentTime.get(Calendar.SECOND)
        val msecond_int = currentTime.get(Calendar.MILLISECOND)

        var hour : String = hour_int.toString()
        if(hour_int < 10)
            hour = "0$hour_int"

        var minute : String = minute_int.toString()
        if(minute_int < 10)
            minute = "0$minute_int"

        var second : String = second_int.toString()
        if(second_int < 10)
            second = "0$second_int"

        var msecond : String = msecond_int.toString()
        if(msecond_int < 10)
            msecond = "00$msecond_int"
        else if(msecond_int < 100)
            msecond = "0$msecond_int"

        return "$hour$minute$second$msecond"
    }
    // GET DIFFERENCE IN TIME
    private fun getTimeDifference(old : String, new : String) : String {

        val hour1 = old.subSequence(0, 2).toString().toInt()
        val hour2 = new.subSequence(0, 2).toString().toInt()

        val minute1 = old.subSequence(2, 4).toString().toInt()
        val minute2 = new.subSequence(2, 4).toString().toInt()

        val second1 = old.subSequence(4, 6).toString().toInt()
        val second2 = new.subSequence(4, 6).toString().toInt()

        val msecond1 = old.subSequence(6, 9).toString().toInt()
        val msecond2 = new.subSequence(6, 9).toString().toInt()

        var hourD = hour2 - hour1
        var minuteD = minute2 - minute1
        var secondD = second2 - second1
        var msecondD = msecond2 - msecond1

        if(msecondD < 0) {
            msecondD += 1000
            secondD--
        }

        if(secondD < 0) {
            secondD += 60
            minuteD--
        }

        if(minuteD < 0) {
            minuteD += 60
            hourD--
        }

        return "$hourD:$minuteD:$secondD:$msecondD"
    }

    // CHECK WHETHER CHARACTERISTIC IS WRITABLE
    private fun BluetoothGattCharacteristic.isWritable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)
    // CHECK WHETHER CHARACTERISTIC CONTAINS A CERTAIN PROPERTY
    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return properties and property != 0
    }

    // FUNCTION RESPONSIBLE FOR WRITING ON THE CNCT_ERR_CHAR CHARACTERISTIC
    private fun writeCharacteristic(payload: ByteArray): Boolean {
        val characteristic = bluetoothGatt.getService(CNCT_ERR_SERV_UUID).getCharacteristic(CNCT_ERR_CHAR_UUID)
        return if(characteristic.isWritable()) {
            Log.e(TAG, "writing message: ${String(payload)}")
            bluetoothGatt.let { gatt ->
                characteristic.writeType = BLUETOOTH_WRITE_TYPE
                characteristic.value = payload
                gatt.writeCharacteristic(characteristic)
            }
            true
        } else {
            Log.e(TAG, "characteristic is not Writable!")
            false
        }
    }

    // MAIN ADMIN AND ERROR HANDLING
    private fun process_image() {
        when {
            // IF IMAGE PRINT WAS A SUCCESS
            print_image() -> {
                // RESET ERROR COUNT
                error_count = 0
                Log.e(TAG, "Image Received successfully!")

                // COMMUNICATION LINK IS NOT BUSY
                link_status = true
            }
            // IF IMAGE PRINT WAS NOT SUCCESSFUL AND WE REACHED 5 ERROR ATTEMPTS
            error_count == 5 -> {
                // RESET ERROR COUNT
                error_count = 0
                Log.e(TAG, "Image received unsuccessfully! --- Error count : $error_count --- Proceeding with next data cycle!")

                // COMMUNICATION LINK IS NOT BUSY
                link_status = true
            }
            // IF IMAGE PRINT WAS NOT SUCCESSFUL AND WE DIDN'T REACH 5 ERROR ATTEMPTS
            else -> {
                // INCREMENT ERROR ATTEMPTS COUNTER
                error_count++
                Log.e(TAG, "Image received unsuccessfully! --- Error count : $error_count --- Resend image!")

                // RESEND IMAGE
                writeCharacteristic("Send Big Image!".toByteArray())
            }
        }
    }
    private fun ByteArray.getUInt42(idx: Int) = ((this[idx + 1].toInt() and 0xFF) shl 8) or
            ((this[idx].toInt() and 0xFF))

    // DATA PROCESSING
    private fun print_image(): Boolean {

        if(packet_img != img_pack && error_count < 5) {
            Log.e(TAG, "Missed packet!!! IMG!!!")
            packet_img = 0
            return false
        }
        packet_img = 0

        var bytes = byteArrayOf()
        var checksum : Long
        for ( b in pic_array) {
            bytes += b.copyOfRange(0, b.size - 4)
            checksum = b.getUIntAt(b.size - 4).toLong()
            Log.e(TAG, "checksum = $checksum")

            val checksum_android = CRC32()
            checksum_android.update(b, 0, b.size - 4)
            val checksum_value : Long = checksum_android.value
            Log.e(TAG, "checksum_android = $checksum_value")

            if(checksum != checksum_value) {
                pic_array.clear()
                Log.e(TAG, "Checksum Invalid!!!")
                return false
            }
        }
        bytes_img = bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

//        val bitmapFlipped = createFlippedBitmap(bitmap, xFlip = true, yFlip = false)

        imgTime = getDataCycleSeconds()
        viewPresetShowIMG(bitmap)
        runImageAnalysis(bitmap)

        saveImageToInternalStorage()
//        saveImageToInternalStorage(bitmapFlipped)

        pic_array.clear()
        return true
    }

    // CONVERT UINT32 TO INTEGER
    private fun ByteArray.getUIntAt(idx: Int) = ((this[idx + 3].toUInt() and 0xFFu) shl 24) or
            ((this[idx + 2].toUInt() and 0xFFu) shl 16) or
            ((this[idx + 1].toUInt() and 0xFFu) shl 8) or
            (this[idx].toUInt() and 0xFFu)

    // IMAGE ANALYSIS
    private fun runImageAnalysis(bitmap : Bitmap) {
        // Aqua 0
//        val bitmap1 = BitmapFactory.decodeResource(resources, R.drawable.aqua0)
//        val imageString = BitMapToString(bitmap1)

        // Actual Image
        val imageString = BitMapToString(bitmap)

        val pyObj = pyObject.callAttr("detect", imageString)
        val str = pyObj.toString()
        val newBitmap = StringToBitMap(str)

        newTime = getDataCycleSeconds()
//        changeSignalLevel5(getTimeDifference(imgTime, newTime))
//        changeSignalLevel6(getTimeDifference(oldTime, newTime))

        viewPresetShowIMG(newBitmap)
    }

    private fun viewPresetShowIMG(bitmap: Bitmap) {
        Log.i(TAG, "Displaying the Image")
        runOnUiThread {
//            imageViewESP.setImageBitmap(bitmap)
        }
    }

    private fun BitMapToString(bitmap: Bitmap?): String? {
        val baos = ByteArrayOutputStream()
        bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val b: ByteArray = baos.toByteArray()
        return Base64.encodeToString(b, Base64.DEFAULT)
    }

    private fun StringToBitMap(encodedString: String?): Bitmap {
        val encodeByte = Base64.decode(encodedString, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(encodeByte, 0, encodeByte.size)
    }

    // VARIABLES
    private lateinit var bytes_img : ByteArray
    private var img_txt_name = 0

    // IMAGE SAVING TO STORAGE
    private fun saveImageToInternalStorage() {
        //         val cw = ContextWrapper(applicationContext)
        // path to /data/data/yourapp/app_data/imageDir
        //         val directory = cw.getDir("imageDir", MODE_PRIVATE)
        val directory = DOWNLOAD_DIR
        // Create file name
        val str_len = deviceName.length
        val devName = deviceName.subSequence(str_len - 4, str_len)
        val txtName = "${devName}_IMG_data$img_txt_name.jpeg"
        //         val txtName = "${devName}_IMG_data$img_txt_name.png"
        img_txt_name++
        // Create imageDir
        val mypath = File(directory, txtName)
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(mypath)
            // Use the compress method on the BitMap object to write image to the OutputStream
            //             bitmapImage?.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            //             bitmapImage?.compress(Bitmap.CompressFormat.PNG, 100, fos)
            Log.e(TAG, "The bytes_img size is : ${bytes_img.size}")
            fos.write(bytes_img, 0, bytes_img.size)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                fos!!.close()
                Log.i(TAG, "The URI is : ${copyFileToDownloads(this, mypath)}")
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        //         return directory.absolutePath
    }

    // CHECK THE FILE EXTENSION OF A FILENAME STRING
    private fun correctifyName(bigName: String): Boolean {
        val len = bigName.length
        val extension = bigName.subSequence(len - 3, len)
        Log.e(TAG, "The extension is : $extension")
        return extension == "txt" // True if .txt --- False if .jpeg
    }

    // PRINT THE FILE TO THE DOWNLOADS FOLDER
    private fun copyFileToDownloads(context: Context, downloadedFile: File): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                val filename = getName(downloadedFile)
                var finalname = ""
                var finalmime = ""
                if(correctifyName(filename)) {
                    finalname = filename.subSequence(0, filename.length - 4).toString()
                    finalmime = "text/plain"
                } else {
                    finalname = filename.subSequence(0, filename.length - 5).toString()
                    finalmime = "image/jpeg"
                }
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalname)
                Log.e(TAG, "The Name is : $finalname")
                put(MediaStore.MediaColumns.MIME_TYPE, finalmime)
                Log.e(TAG, "The MimeType is : $finalmime")
                put(MediaStore.MediaColumns.SIZE, getFileSize(downloadedFile))
                Log.e(TAG, "The Size is : ${getFileSize(downloadedFile)} and Length : ${downloadedFile.length()}")
            }
//            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

//            // Projection
//            val projection = arrayOf(
//                MediaStore.Downloads._ID,
//                MediaStore.Downloads.DISPLAY_NAME,
//                MediaStore.Downloads.DATA)
//
//            // Match on the file path
//            val selection = MediaStore.Downloads.DATA + " = ?"
//            val selectionArgs = arrayOf(downloadedFile.absolutePath)
//
//            // Query for the ID of the media matching the file path
//            val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
////            val contentResolver = contentResolver
//            val c: Cursor? = resolver.query(queryUri, projection, selection, selectionArgs, null)
//            Log.e(TAG, "Query successful $c")
//
//            if (c!!.moveToFirst()) {
//                // We found the ID. Deleting the item via the content provider will also remove the file
//                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
//                val deleteUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
//                val numDel = resolver.delete(deleteUri, null, null)
//                Log.e(TAG, "Deleted $numDel entries")
//            }
//
//            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
//            val num_del = contentResolver.delete(
//                uri,
//                MediaStore.MediaColumns.DISPLAY_NAME + "=\"" + getName(downloadedFile) + "\"",
//                null
//            )
//            Log.e(TAG, "The number of rows deleted are : $num_del")

            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            val authority = "${context.packageName}.provider"
//            val authority = context.packageName
//            val authority = ".com.example.android.aam.provider"
//            val authority = ".com.example.android.aam"
            val destinyFile = File(getCustomDirectory2(), getName(downloadedFile))
            Log.i(TAG, "Build version is else")

            FileProvider.getUriForFile(context, authority, destinyFile)
        }?.also { downloadedUri ->
            Log.e(TAG, "Downloaded URI is : $downloadedUri")
            resolver.openOutputStream(downloadedUri).use { outputStream ->
                Log.e(TAG, "OutputStream is : $outputStream")
                val brr = ByteArray(655360)
                var len: Int
                val bufferedInputStream = BufferedInputStream(FileInputStream(downloadedFile.absoluteFile))
                while ((bufferedInputStream.read(brr, 0, brr.size).also { len = it }) != -1) {
                    outputStream?.write(brr, 0, len)
                }
                outputStream?.flush()
                bufferedInputStream.close()
            }
        }
    }

    // GET THE NAME OF A FILE
    private fun getName(file: File): String{
        val fullPath = file.absolutePath
        //val directory = fullPath.substringBeforeLast("/")
        val fullName = fullPath.substringAfterLast("/")
        //val fileName = fullName.substringBeforeLast(".")
        //val extension = fullName.substringAfterLast(".")
        return fullName
    }

    // RETURN THE DOWNLOADS FOLDER
    private fun getCustomDirectory2() : File {
        var count = 1
        var name = nowThereIsNoTimeAtAll()
        Log.e(TAG, name)
        val parent = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
//        val parent = getExternalFilesDir(null)
        var folder = File(parent, name)
        Log.e(TAG, "Creating folder $name")

        while(folder.exists()) {
            // Update count and create again
            count++
            name = "AIM Data $count"
            folder = File(parent, name)
            Log.e(TAG, "Creating folder $name")
        }

        // Create the directory
        try {
            folder.mkdirs()

            Log.e(TAG, "Folder ${folder.name} created successfully!")
            Log.e(TAG, "Path : ${folder.absolutePath}")
        } catch (e : SecurityException) {
            Log.w(TAG, e)
            Log.e(TAG, "Folder ${folder.name} failed!")
        }

        return folder
    }

    // GET THE SIZE OF A FILE
    private fun getFileSize(file:File): Int {
        return Integer.parseInt((file.length()/1024).toString())
    }

    // CONVERT BYTEARRAY TO HEXADECIMAL
    private fun ByteArray.toHexString(): String = joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }
}