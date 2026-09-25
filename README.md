# 🏍️ MotoIntercom

**MotoIntercom** es una aplicación Android de intercomunicación de voz en tiempo real y transmisión de música compartida diseñada específicamente para motociclistas en ruta. Opera directamente a través de **WiFi Local y Hotspot** sin depender de datos móviles ni servidores en la nube.

---

## 🚀 Características Principales

### 🎙️ 1. Audio y Conferencia en Tiempo Real
- **HD Voice (16.000 Hz / 16-bit PCM)**: Muestreo de alta fidelidad para claridad cristalina de la voz.
- **Filtro Digital Butterworth Anti-Viento (120 Hz)**: Suprime frecuencias sub-graves producidas por turbulencias de viento en el casco y escapes de motor.
- **Cancelación de Ruido y Control de Ganancia Nativo**: Integración con el hardware DSP (`NoiseSuppressor`, `AcousticEchoCanceler` y `AutomaticGainControl`).
- **Packet Loss Concealment (PLC)**: Atenuación suave exponencial en tramas perdidas por UDP para evitar clics y pops digitales.
- **Jitter Buffer Adaptativo Dinámico**: Buffer elástico de baja latencia (<100 ms) que amortigua ráfagas de red en carretera.

### 🔒 2. Seguridad y Cifrado
- **Cifrado AES-128 en Tiempo Real (AES-CTR)**: Cifrado simétrico de baja latencia por paquete con vector de inicialización (IV) dinámico de 16 bytes.
- **Zero-Allocation Memory Architecture**: Reutilización de ciphers mediante `ThreadLocal` y descifrado directo en buffers de red sin recolección de basura (GC-free).

### 🎧 3. Bluetooth SCO HD & Compatibilidad de Cascos
- **Cascos y Manos Libres para Moto**: Compatible con Cardo, Sena, FreedConn, Lexin y auriculares Bluetooth estándar.
- **Modo Dúplex mSBC (16 kHz)**: Enrutamiento de micrófono integrado en el casco mediante `AudioRouteManager` (Android 12+ `setCommunicationDevice` y fallback SCO).
- **Conmutación Inteligente**: Alterna automáticamente entre el altavoz exterior del teléfono y el intercomunicador del casco.

### 🎵 4. Compartición de Música y Audio Multitasking
- **Transmisión de Música en Grupo**: Un integrante puede ser el DJ y retransmitir pistas de audio locales o audio del sistema (Spotify, YouTube Music) a todos los motociclistas de la sala.
- **Auto-Ducking Dinámico**: El volumen de la música se reduce automáticamente al 20% cuando alguien habla en el canal de voz.
- **Audio Multitasking**: Permite escuchar navegación GPS o música personal en el casco simultáneamente sin interrumpir la conferencia.

### ☀️🌧️ 5. Modos Especiales para Conducción en Moto
- **☀️ Modo Sol (High-Contrast Outdoor)**: Paleta de alto contraste solar para pantallas ancladas al manillar con sol directo y gafas polarizadas.
- **🌧️ Modo Lluvia (Water / Rain Lock)**: Bloquea la pantalla contra toques fantasma ("phantom touches") provocados por gotas de lluvia, manteniendo el botón PTT accesible y requiriendo una retención de 1.5s para desbloquear.

---

## 🛠️ Tecnologías

- **Lenguaje**: Kotlin 100%
- **UI Toolkit**: Jetpack Compose (Material 3)
- **Inyección de Dependencias**: Hilt / Dagger
- **Arquitectura**: MVVM + Clean Architecture + Coroutines / StateFlow
- **Audio Engine**: Android AudioRecord + AudioTrack + AudioManager en modo comunicación
- **Networking**: UDP Datagrams + Multicast Beacon Discovery
- **SDK**: Min SDK 24 (Android 7.0) | Target SDK 34 (Android 14)

---

## 📦 Compilación

### Requisitos
- Android Studio Ladybug / Koala o superior
- JDK 17
- Android SDK 34

### Comandos de Compilación
```bash
# Compilar versión Debug
./gradlew assembleDebug

# Compilar versión Release optimizada
./gradlew assembleRelease
```

---

## 📄 Licencia

Este proyecto está bajo la Licencia MIT.
