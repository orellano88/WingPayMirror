from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.clock import Clock
import threading

# --- PROTOCOLO JARVIS-QWEN v34.0: ESTRUCTURA DE MENSAJES STARK ---
# 1. Layout Interno con size_hint_y: None y height: self.minimum_height (REQUERIDO)
# 2. Función update_scroll para AutoScroll al final.
# 3. Limpieza de TextInput tras envío.
# 4. Hilos (Threading) para no bloquear el MainThread.

class MessageBubble(Label):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.size_hint_y = None
        self.text_size = (400, None) # Ajuste de ancho de mensaje
        self.padding = (10, 10)
        self.bind(texture_size=self.setter('size'))

class WingPayChat(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'vertical'
        self.padding = 10
        self.spacing = 10

        # ScrollView Configurado con el Layout Interno que solicita
        self.scroll = ScrollView(size_hint=(1, 1), do_scroll_x=False)
        self.msg_container = BoxLayout(orientation='vertical', size_hint_y=None, spacing=10)
        self.msg_container.bind(minimum_height=self.msg_container.setter('height')) # size_hint_y: None & height: self.minimum_height

        self.scroll.add_widget(self.msg_container)
        self.add_widget(self.scroll)

        # Entrada de Texto y Botón de Envío
        self.input_area = BoxLayout(size_hint_y=None, height=50, spacing=10)
        self.text_input = TextInput(multiline=False, size_hint_x=0.8)
        self.send_btn = Button(text='ENVIAR', size_hint_x=0.2)
        self.send_btn.bind(on_press=self.send_message)

        self.input_area.add_widget(self.text_input)
        self.input_area.add_widget(self.send_btn)
        self.add_widget(self.input_area)

    def send_message(self, instance):
        msg = self.text_input.text.strip()
        if msg:
            # 1. Limpieza del TextInput (INMEDIATA)
            self.text_input.text = ""
            
            # 2. Envío en Hilo Secundario (NO bloquea el MainThread)
            threading.Thread(target=self.process_sending, args=(msg,)).start()

    def process_sending(self, msg):
        # Simulación de Envío a la Nube (Aquí iría su lógica de Firebase/Server)
        # Una vez enviado, actualizamos la UI en el MainThread usando Clock
        Clock.schedule_once(lambda dt: self.add_message_to_ui(f"Tú: {msg}"), 0)

    def add_message_to_ui(self, msg_text):
        new_msg = MessageBubble(text=msg_text, color=(0, 0, 0, 1))
        self.msg_container.add_widget(new_msg)
        
        # 3. Función update_scroll: AutoScroll al final al recibir mensaje
        self.update_scroll()

    def update_scroll(self, *args):
        # Pequeño delay para asegurar que el widget se ha renderizado
        Clock.schedule_once(lambda dt: setattr(self.scroll, 'scroll_y', 0), 0.1)

class WingPayApp(App):
    def build(self):
        return WingPayChat()

if __name__ == '__main__':
    WingPayApp().run()
