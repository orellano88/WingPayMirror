import os
import threading
import requests
import json
from datetime import datetime
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.recycleview import RecycleView
from kivy.uix.image import AsyncImage
from kivy.graphics import Color, RoundedRectangle, Line
from kivy.clock import Clock, mainthread
from kivy.core.window import Window
from kivy.properties import StringProperty, BooleanProperty, ListProperty, NumericProperty
from kivy.lang import Builder

# --- CONFIGURACIÓN DE PANTALLA STARK ---
Window.softinput_mode = "below_target"
Window.clearcolor = (0.94, 0.95, 0.96, 1)

try:
    from plyer import tts, vibrator, notification, camera, filechooser
except ImportError:
    tts = vibrator = notification = camera = filechooser = None

# --- COMPONENTE: BURBUJA DE MENSAJE WHATSAPP ---
class MessageBubble(BoxLayout):
    text = StringProperty("")
    source = StringProperty("")
    is_user = BooleanProperty(True)
    is_payment = BooleanProperty(False)
    bank = StringProperty("YAPE")
    time = StringProperty("")
    bg_color = ListProperty([1, 1, 1, 1])
    border_color = ListProperty([1, 1, 1, 1])
    halign = StringProperty("right")
    has_image = BooleanProperty(False)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'vertical'
        self.size_hint_y = None
        self.spacing = 5
        self.bind(minimum_height=self.setter('height'))

# --- MOTOR PRINCIPAL: WING PAY SENTINEL v37.7 (STARK-QWEN FINAL) ---
class WingPaySentinel(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'vertical'
        self.setup_persistence()
        self.start_omega_sync()

    def setup_persistence(self):
        # Mantiene el CPU encendido incluso con pantalla apagada
        if vibrator:
            try:
                from plyer import wakelock
                wakelock.acquire()
                print("[SISTEMA] WakeLock Adquirido: El Centinela no dormirá.")
            except Exception as e:
                print(f"[ERROR] No se pudo activar WakeLock: {e}")

    def request_emui_permissions(self):
        try:
            from kivy.utils import platform
            if platform == 'android':
                from jnius import autoclass
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                Intent = autoclass('android.content.Intent')
                Settings = autoclass('android.provider.Settings')
                currentActivity = PythonActivity.mActivity
                
                # Abrir Accesos a Notificaciones
                intent_notif = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                currentActivity.startActivity(intent_notif)
                
                # Abrir Ignorar Optimizaciones de Batería
                intent_bat = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                currentActivity.startActivity(intent_bat)
        except Exception as e:
            self.add_message(f"Error abriendo permisos: {e}", is_user=False)

    def start_omega_sync(self):
        # Iniciar Escucha Global (WhatsApp Web Style)
        threading.Thread(target=self.ntfy_listener_task, daemon=True).start()

    def ntfy_listener_task(self):
        topic = "wingpay_stark_8502345704"
        url = f"https://ntfy.sh/{topic}/json"
        print(f"[SYNC] Conectando a Red Stark: {topic}")
        
        while True:
            try:
                with requests.get(url, stream=True, timeout=60) as r:
                    for line in r.iter_lines():
                        if line:
                            data = json.loads(line)
                            if "message" in data:
                                # El mensaje viene como JSON string en el campo 'message' o como el cuerpo
                                try:
                                    msg_data = json.loads(data["message"])
                                    self.handle_remote_payment(msg_data)
                                except:
                                    # Si no es JSON, es un mensaje de texto plano
                                    pass
            except Exception as e:
                print(f"[SYNC] Error de conexión: {e}. Reintentando en 10s...")
                Clock.tick() # Mantener vivo
                import time
                time.sleep(10)

    @mainthread
    def handle_remote_payment(self, data):
        bank = data.get("bank", "YAPE")
        name = data.get("name", "Cliente")
        amt = data.get("amt", "0.00")
        
        details = f"S/ {amt} de {name}"
        self.intercept_payment(bank, details, remote=True)

    def trigger_panic(self):
        if vibrator: vibrator.vibrate(0.5)
        self.add_message("🚨 ALARMA DE PÁNICO ACTIVADA 🚨", is_user=True)

    def select_media(self):
        # Lógica para adjuntar imagen (Solicitada por el Usuario)
        if filechooser:
            filechooser.open_file(on_selection=self._on_selection)
        else:
            # Simulación si no hay Plyer
            self.add_message("📎 Abriendo Galería...", is_user=True)
            Clock.schedule_once(lambda dt: self.add_message("Imagen seleccionada (Simulado)", is_user=True, source="atlas://data/images/defaulttheme/image-missing"), 1)

    def _on_selection(self, selection):
        if selection:
            self.display_media(selection[0])

    def send_action(self, text_input):
        msg = text_input.text.strip()
        if msg:
            text_input.text = "" # Limpieza instantánea
            self.add_message(msg, is_user=True)
            
            if "yape" in msg.lower() or "bcp" in msg.lower():
                self.intercept_payment("YAPE" if "yape" in msg.lower() else "BCP", f"S/ 50.00 de Cliente {msg.upper()}")

    @mainthread
    def add_message(self, text, is_user=True, is_payment=False, bank="YAPE", source=""):
        if is_payment:
            bg = [0.85, 0.98, 0.9, 1] if bank == "YAPE" else [1, 0.92, 0.8, 1]
            border = [0.1, 0.6, 0.2, 1] if bank == "YAPE" else [1, 0.5, 0, 1]
            align = "center"
        else:
            bg = [0.86, 0.97, 0.77, 1] if is_user else [1, 1, 1, 1]
            border = [0.7, 0.9, 0.6, 1] if is_user else [0.9, 0.9, 0.9, 1]
            align = "right" if is_user else "left"

        new_entry = {
            "text": text,
            "source": source,
            "has_image": True if source else False,
            "is_user": is_user,
            "is_payment": is_payment,
            "bank": bank,
            "time": datetime.now().strftime("%H:%M"),
            "bg_color": bg,
            "border_color": border,
            "halign": align
        }
        
        if len(self.ids.rv.data) > 200:
            self.ids.rv.data.pop(0)
            
        self.ids.rv.data.append(new_entry)
        self.ids.rv.scroll_y = 0

    def display_media(self, path):
        ext = os.path.splitext(path)[1].lower()
        if ext in ['.jpg', '.png', '.jpeg', '.webp']:
            threading.Thread(target=self._load_media_task, args=(path,)).start()
        else:
            self.add_message(f"Archivo: {os.path.basename(path)}", is_user=True)

    def _load_media_task(self, path):
        Clock.schedule_once(lambda dt: self.add_message(f"Foto: {os.path.basename(path)}", is_user=True, source=path), 0)

    def intercept_payment(self, bank, details, remote=False):
        # 1. Registro Visual en la App
        self.add_message(f"💰 {bank} CONFIRMADO\n{details}", is_user=False, is_payment=True, bank=bank)
        
        # 2. Lector Inteligente
        monto = "un pago"
        if "S/" in details:
            parts = details.split("S/")
            if len(parts) > 1:
                monto = f"S/ {parts[1].split()[0]}"
        
        nombre = details.replace(f"por {monto}", "").replace(monto, "").replace("de", "").strip()
        
        # Frase exacta para el lector (MEJORADA)
        speech = f"Atención. Pago recibido en {bank}. {nombre} envió {monto}."
        
        if tts: 
            threading.Thread(target=lambda: tts.speak(speech)).start()
        
        # 3. Protocolo Omega Sync (Solo si es local)
        if not remote:
            threading.Thread(target=self.broadcast_to_mirror, args=(bank, nombre, monto)).start()

    def broadcast_to_mirror(self, bank, nombre, monto):
        topic = "wingpay_stark_8502345704"
        url = f"https://ntfy.sh/{topic}"
        try:
            payload = json.dumps({"bank": bank, "name": nombre, "amt": monto})
            requests.post(url, data=payload, headers={"Title": f"PAGO {bank}"})
            print(f"[SYNC] Sincronizado con espejo: {bank}")
        except Exception as e:
            print(f"[SYNC] Error al sincronizar: {e}")

class WingPayApp(App):
    def build(self):
        return Builder.load_string('''
<MessageBubble>:
    padding: [10, 5]
    AnchorLayout:
        anchor_x: root.halign
        BoxLayout:
            orientation: 'vertical'
            size_hint: None, None
            width: min(Window.width * 0.75, self.minimum_width + 30) if not root.has_image else '260dp'
            height: self.minimum_height
            padding: [12, 10]
            canvas.before:
                Color:
                    rgba: root.bg_color
                RoundedRectangle:
                    pos: self.pos
                    size: self.size
                    radius: [15, 15, 0, 15] if root.is_user else [15, 15, 15, 0]
                Color:
                    rgba: root.border_color
                Line:
                    width: 1.1
                    rounded_rectangle: (self.x, self.y, self.width, self.height, 15, 15, 0, 15) if root.is_user else (self.x, self.y, self.width, self.height, 15, 15, 15, 0)

            AsyncImage:
                source: root.source
                size_hint_y: None
                height: '220dp' if root.has_image else 0
                opacity: 1 if root.has_image else 0
                allow_stretch: True

            Label:
                text: root.text
                color: 0, 0, 0, 1
                font_size: '16sp'
                size_hint: 1, None
                height: self.texture_size[1]
                text_size: self.width, None
                halign: 'left'
                bold: root.is_payment

            Label:
                text: root.time
                color: 0.5, 0.5, 0.5, 1
                font_size: '11sp'
                size_hint_y: None
                height: '18dp'
                halign: 'right'
                text_size: self.size

WingPaySentinel:
    BoxLayout:
        orientation: 'vertical'
        canvas.before:
            Color:
                rgba: 0.94, 0.95, 0.96, 1
            Rectangle:
                pos: self.pos
                size: self.size

        # CABECERA
        BoxLayout:
            size_hint_y: None
            height: '70dp'
            padding: '12dp'
            canvas.before:
                Color:
                    rgba: 0.04, 0.3, 0.35, 1
                Rectangle:
                    pos: self.pos
                    size: self.size
            Label:
                text: "WING PAY SENTINEL v37.7"
                bold: True
                font_size: '18sp'
            Button:
                text: "🛠 PERMISOS"
                size_hint_x: None
                width: '100dp'
                background_color: 0.8, 0.6, 0.1, 1
                on_release: root.request_emui_permissions()
            Button:
                text: "🧪 TEST"
                size_hint_x: None
                width: '70dp'
                background_color: 0.1, 0.5, 0.8, 1
                on_release: root.broadcast_to_mirror("YAPE", "PRUEBA STARK", "1.00")
            Button:
                text: "🚨"
                size_hint_x: None
                width: '50dp'
                background_color: 1, 0, 0, 1
                on_release: root.trigger_panic()

        # CHAT
        RecycleView:
            id: rv
            viewclass: 'MessageBubble'
            RecycleBoxLayout:
                default_size: None, None
                default_size_hint: 1, None
                size_hint_y: None
                height: self.minimum_height
                orientation: 'vertical'
                spacing: '12dp'
                padding: '10dp'

        # BARRA WHATSAPP (CON ADJUNTO)
        BoxLayout:
            size_hint_y: None
            height: '75dp'
            padding: '8dp'
            spacing: '10dp'
            canvas.before:
                Color:
                    rgba: 1, 1, 1, 1
                Rectangle:
                    pos: self.pos
                    size: self.size

            Button:
                text: "📎"
                size_hint_x: None
                width: '50dp'
                background_normal: ''
                background_color: 0.9, 0.9, 0.9, 1
                color: 0,0,0,1
                font_size: '22sp'
                on_release: root.select_media()

            TextInput:
                id: ti
                hint_text: "Escribe un mensaje..."
                multiline: False
                on_text_validate: root.send_action(ti)
            
            Button:
                text: "➤"
                size_hint_x: None
                width: '60dp'
                background_color: 0.04, 0.6, 0.35, 1
                on_release: root.send_action(ti)
''')

if __name__ == '__main__':
    WingPayApp().run()
