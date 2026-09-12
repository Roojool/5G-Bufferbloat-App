import hashlib
import unittest
from socket_endpoint import BLOCK, chunks, expected_hash, transfer


class Peer:
    def __init__(self, error=False):
        self.data = bytearray()
        self.error = error

    def settimeout(self, _):
        pass

    def send(self, data):
        if self.error:
            raise OSError(104, "private details must not escape")
        n = min(7, len(data))
        self.data.extend(data[:n])
        return n


class EndpointTests(unittest.TestCase):
    def test_pattern_and_boundary_hashes(self):
        for count in (1, 250, 251, len(BLOCK), len(BLOCK)+1, 100_000):
            expected = bytes(i % 251 for i in range(count))
            self.assertEqual(expected, b"".join(chunks(count)))
            self.assertEqual(hashlib.sha256(expected).hexdigest(), expected_hash(count))

    def test_partial_sends_exact_count_and_hash(self):
        peer = Peer()
        result = transfer(peer, 17000, 10, lambda _: None)
        self.assertEqual("COMPLETE", result["outcome"])
        self.assertEqual(17000, len(peer.data))
        self.assertEqual(expected_hash(17000), result["sha256"])

    def test_errno_redaction(self):
        result = transfer(Peer(True), 10, 1, lambda _: None)
        self.assertEqual(104, result["errno"])
        self.assertNotIn("private", str(result))

    def test_bounds(self):
        for count in (0, -1, 256*1024*1024+1):
            with self.assertRaises(ValueError):
                expected_hash(count)


if __name__ == "__main__":
    unittest.main()
