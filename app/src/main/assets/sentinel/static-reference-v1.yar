import "dex"

rule foxhole_dynamic_loader_reference {
    strings:
        $dex_class_loader = "Ldalvik/system/DexClassLoader;" ascii
        $memory_loader = "Ldalvik/system/InMemoryDexClassLoader;" ascii
    condition:
        dex.is_dex and any of them
}

rule foxhole_process_api_reference {
    strings:
        $runtime = "Ljava/lang/Runtime;" ascii
        $process_builder = "Ljava/lang/ProcessBuilder;" ascii
    condition:
        dex.is_dex and any of them
}

rule foxhole_accessibility_api_reference {
    strings:
        $service = "Landroid/accessibilityservice/AccessibilityService;" ascii
    condition:
        dex.is_dex and $service
}

rule foxhole_sms_api_reference {
    strings:
        $manager = "Landroid/telephony/SmsManager;" ascii
    condition:
        dex.is_dex and $manager
}
