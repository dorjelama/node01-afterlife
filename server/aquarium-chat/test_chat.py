"""The parts of chat.py that decide things, without a model or a phone: python3 -m unittest test_chat"""
import unittest

import chat

NOW = 1_795_694_400_000  # 2026-11-26 12:00 UTC
DAY = 86_400_000
TANK = {
    "kind": "low-tech planted", "litres": 60, "livestock": "6 neon tetras", "plants": "hornwort",
    "jobs": [
        {"job": "FEED", "name": "Feeding", "every": 1, "last": NOW - 3 * 3_600_000, "due": NOW + 21 * 3_600_000},
        {"job": "WATER_CHANGE", "name": "Water change", "every": 7, "last": NOW - 9 * DAY, "due": NOW - 2 * DAY},
        {"job": "FILTER", "name": "Filter clean", "every": 30, "last": None, "due": NOW + 20 * DAY},
    ],
    "risk": "building", "why": ["9 days since the last water change"], "lightOn": "7:00", "lightHours": 9.5,
    "watching": True, "described": "2026-11-26 12:10  A planted aquarium with clear water.",
}


class Route(unittest.TestCase):
    def test_tiers(self):
        self.assertEqual(0, chat.route("When did I last change the water?"))
        self.assertEqual(0, chat.route("when was the filter cleaned"))
        self.assertEqual(0, chat.route("How long has the light been on today?"))
        self.assertEqual(3, chat.route("Look at the tank: is there algae?"))
        self.assertEqual(2, chat.route("Why are my plant leaves melting?"))
        self.assertEqual(2, chat.route("Explain the nitrogen cycle"))
        self.assertEqual(1, chat.route("Is my tank healthy today?"))
        self.assertEqual(1, chat.route("hello"))


class DataAnswers(unittest.TestCase):
    def test_jobs(self):
        a = chat.data_answer("When did I last change the water?", TANK, [], NOW)
        self.assertIn("Water change: last done 9 days ago", a)
        self.assertIn("overdue by 2.0 days", a)
        self.assertIn("last done 3 hours ago", chat.data_answer("when did I last feed them", TANK, [], NOW))
        self.assertIn("No filter clean is logged yet", chat.data_answer("when did I last clean the filter", TANK, [], NOW))

    def test_light(self):
        from datetime import datetime
        today = datetime.fromtimestamp(NOW / 1000).strftime("%Y-%m-%d")
        a = chat.data_answer("how long has the light been on", TANK, [f"{today},300,4.6,3,0.19,0.387,82.1,1"], NOW)
        self.assertIn("about 5.0 hours today", a)
        self.assertIn("usually on at 7:00 for 9.5 hours", a)
        self.assertIn("not seen the light on today", chat.data_answer("how long has the light been on", TANK, [], NOW))

    def test_no_phone_no_guess(self):
        self.assertIsNone(chat.data_answer("When did I last change the water?", None, [], NOW))


class Knowledge(unittest.TestCase):
    def test_tank_lines(self):
        lines = chat.tank_lines(TANK, NOW)
        self.assertEqual("Tank: low-tech planted, 60 litres, fish: 6 neon tetras, plants: hornwort.", lines[0])
        self.assertIn("Water change: last 9 days ago, every 7 days, overdue by 2.0 days.", lines)
        self.assertIn("Filter clean: not logged yet, every 30 days, due in 20.0 days.", lines)
        self.assertTrue(any("(can be wrong): A planted aquarium with clear water." in l for l in lines))
        self.assertIn("not answering", chat.tank_lines(None, NOW)[0])

    def test_days_lines(self):
        rows = ["date,lit_minutes,activity,moving_at_once,plants,green,clarity,feeds", "2026-11-26,300,4.60,3,0.190,0.387,82.1,1"]
        self.assertEqual(["2026-11-26: lit 5.0 h, activity 4.60, up to 3 moving at once, plants 19% of picture, "
                          "green 0.387, clarity 82.1, fed 1x"], chat.days_lines(rows))


class Memory(unittest.TestCase):
    def test_new_facts_ignore_case_and_repeats(self):
        known = ["The owner has 6 neon tetras."]
        found = ["the owner has 6 neon tetras", "The heater is set to 26 °C.", "The heater is set to 26 °C!"]
        self.assertEqual(["The heater is set to 26 °C."], chat.new_facts(found, known))


class Paths(unittest.TestCase):
    def test_chat_ids_only(self):
        self.assertTrue(chat.chat_path("0123456789ab").endswith("0123456789ab.json"))
        for bad in ["../../etc/passwd", "0123456789ab/../x", "", "0123456789AB"]:
            with self.assertRaises(ValueError):
                chat.chat_path(bad)


if __name__ == "__main__":
    unittest.main()
