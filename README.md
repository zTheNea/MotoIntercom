# MotoIntercom

MotoIntercom es una aplicación Android de intercomunicación de voz en tiempo real y transmisión de música compartida diseñada para motociclistas. Funciona a través de WiFi Local y Hotspot sin requerir conexión a internet ni servidores externos.

---

## Características Principales

### 1. Audio y Conferencia en Tiempo Real
- HD Voice (16.000 Hz / 16-bit PCM): Muestreo de alta fidelidad para claridad de voz.
- Filtro Digital Butterworth Anti-Viento (120 Hz): Atenúa ruidos de baja frecuencia causados por turbulencias de aire y escapes.
- Procesamiento de Audio Integrado: Cancelación de ruido acústico, supresión de eco y control automático de ganancia.
- Packet Loss Concealment (PLC): Atenuación gradual de paquetes perdidos en UDP para suprimir chasquidos y ruidos digitales.
- Jitter Buffer Adaptativo: Buffer dinámico de baja latencia para absorber fluctuaciones de red.

### 2. Seguridad y Cifrado
- Cifrado AES-128 en Tiempo Real (AES-CTR): Cifrado simétrico por paquete con vector de inicialización de 16 bytes.
- Arquitectura Zero-Allocation: Reutilización de instancias de cifrado en ThreadLocal y descifrado directo en memoria para evitar pausas de recolección de basura.

### 3. Bluetooth SCO HD y Soporte de Cascos
- Compatibilidad con Cascos de Moto: Compatible con intercomunicadores Bluetooth de diversas marcas (Cardo, Sena, FreedConn, etc.).
- Audio Dúplex mSBC (16 kHz): Habilita el canal de voz y micrófono integrado del casco mediante AudioManager en modo comunicación.
- Enrutamiento Inteligente: Selección dinámica entre auriculares/casco y altavoz del dispositivo.

### 4. Transmisión de Música y Multitarea de Audio
- Música Compartida: Transmisión sincronizada de archivos locales o audio del sistema entre motociclistas conectados.
- Atenuación Automática (Auto-Ducking): Reducción del volumen de música en segundo plano cuando se detecta voz en el canal.
- Multitarea de Audio: Permite escuchar música personal y navegación GPS sin interrumpir la comunicación.

### 5. Modos de Conducción
- Modo Sol: Interfaz de alto contraste en blanco y negro para visibilidad bajo luz solar directa en el manillar.
- Modo Lluvia: Protección contra toques involuntarios causados por agua en pantalla táctil, con botón PTT accesible y desbloqueo seguro manteniendo presionado por 1.5 segundos.

---

## Tecnologías

- Lenguaje: Kotlin
- Interfaz: Jetpack Compose (Material 3)
- Inyección de Dependencias: Hilt
- Arquitectura: MVVM + Corrutinas + StateFlow
- Motor de Audio: Android AudioRecord y AudioTrack
- Red: Datagramas UDP y descubrimiento por balizas locales
- Compatibilidad: Android 7.0 (API 24) a Android 14 (API 34)

---

## Compilación

### Requisitos
- Android Studio
- JDK 17
- Android SDK 34

### Comandos
```bash
# Compilar APK Debug
./gradlew assembleDebug

# Compilar APK Release
./gradlew assembleRelease
```

---

## Licencia

Distribuido bajo la Licencia MIT.
