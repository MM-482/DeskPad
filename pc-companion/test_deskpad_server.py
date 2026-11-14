import json
import unittest
from deskpad_server import NoopOrXdotoolInjector, handle_tcp_command
from unittest import mock


class TestDeskPadServer(unittest.TestCase):
    def setUp(self):
        with mock.patch('deskpad_server.subprocess.run') as run:
            run.return_value.returncode = 1
            self.inj = NoopOrXdotoolInjector()

    def test_authenticated_command_dispatch_move_scroll_zoom(self):
        handle_tcp_command('{"t":"move","dx":12,"dy":-7}', self.inj)
        handle_tcp_command('{"t":"scroll","sx":0,"sy":4}', self.inj)
        handle_tcp_command('{"t":"zoom","delta":-2}', self.inj)
        self.assertEqual(
            self.inj.events,
            [("move", 12, -7), ("scroll", 0, 4), ("zoom", -2)],
        )

    def test_tcp_ping_returns_pong(self):
        reply = handle_tcp_command('{"t":"ping","ts":123456}', self.inj)
        self.assertEqual(json.loads(reply), {"t": "pong", "ts": 123456})

    def test_tcp_click_text_key(self):
        handle_tcp_command('{"t":"click","b":"R","c":1}', self.inj)
        handle_tcp_command('{"t":"text","v":"hello"}', self.inj)
        handle_tcp_command('{"t":"key","k":"c","m":["CTRL"]}', self.inj)
        self.assertEqual(
            self.inj.events,
            [
                ("click", "R", 1),
                ("text", "hello"),
                ("key", "c", ["CTRL"]),
            ],
        )


if __name__ == "__main__":
    unittest.main()
