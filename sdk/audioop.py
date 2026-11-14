"""Pure-Python stand-in for the `audioop` module, which Python 3.13 removed.

Plugins written for exteraGram (CPython 3.11) still import it. Samples are signed integers of 1, 2 or 4 bytes
in native byte order, like the original.
"""
import array
import sys

__all__ = ["error", "add", "avg", "bias", "getsample", "lin2lin", "max", "maxpp", "minmax", "mul", "ratecv",
           "reverse", "rms", "tomono", "tostereo"]

_builtin_max = max
_builtin_min = min


class error(Exception):
    pass


_CODES = {1: "b", 2: "h", 4: "i"}


def _array(fragment, width):
    if width not in _CODES:
        raise error("Size should be 1, 2 or 4")
    data = array.array(_CODES[width])
    if data.itemsize != width:
        raise error("Size should be 1, 2 or 4")
    raw = bytes(fragment)
    if len(raw) % width:
        raise error("not a whole number of frames")
    data.frombytes(raw)
    return data


def _limits(width):
    bits = width * 8
    return -(1 << (bits - 1)), (1 << (bits - 1)) - 1


def _clip(value, width):
    low, high = _limits(width)
    value = int(value)
    return low if value < low else high if value > high else value


def _trunc_div(a, b):
    return -((-a) // b) if a < 0 else a // b


def _pack(values, width):
    return array.array(_CODES[width], values).tobytes()


def getsample(fragment, width, index):
    data = _array(fragment, width)
    if not 0 <= index < len(data):
        raise error("Index out of range")
    return data[index]


def max(fragment, width):
    data = _array(fragment, width)
    return _builtin_max((abs(v) for v in data), default=0)


def minmax(fragment, width):
    data = _array(fragment, width)
    if not data:
        low, high = _limits(width)
        return high, low
    return _builtin_min(data), _builtin_max(data)


def avg(fragment, width):
    data = _array(fragment, width)
    return _trunc_div(sum(data), len(data)) if data else 0


def rms(fragment, width):
    data = _array(fragment, width)
    if not data:
        return 0
    return int((sum(v * v for v in data) / len(data)) ** 0.5)


def maxpp(fragment, width):
    data = _array(fragment, width)
    if len(data) < 2:
        return 0
    best = 0
    last = data[0]
    direction = None
    extreme = last
    for value in data[1:]:
        if value == last:
            continue
        now = value > last
        if direction is not None and now != direction:
            best = _builtin_max(best, abs(last - extreme))
            extreme = last
        direction = now
        last = value
    return _builtin_max(best, abs(last - extreme))


def bias(fragment, width, offset):
    data = _array(fragment, width)
    bits = 1 << (width * 8)
    out = []
    for value in data:
        value = (value + offset) % bits
        if value >= bits // 2:
            value -= bits
        out.append(value)
    return _pack(out, width)


def mul(fragment, width, factor):
    data = _array(fragment, width)
    return _pack([_clip(v * factor, width) for v in data], width)


def add(fragment1, fragment2, width):
    a = _array(fragment1, width)
    b = _array(fragment2, width)
    if len(a) != len(b):
        raise error("Lengths should be the same")
    return _pack([_clip(x + y, width) for x, y in zip(a, b)], width)


def reverse(fragment, width):
    data = _array(fragment, width)
    data.reverse()
    return data.tobytes()


def tomono(fragment, width, lfactor, rfactor):
    data = _array(fragment, width)
    if len(data) % 2:
        raise error("not a whole number of frames")
    return _pack([_clip(data[i] * lfactor + data[i + 1] * rfactor, width) for i in range(0, len(data), 2)], width)


def tostereo(fragment, width, lfactor, rfactor):
    data = _array(fragment, width)
    out = []
    for value in data:
        out.append(_clip(value * lfactor, width))
        out.append(_clip(value * rfactor, width))
    return _pack(out, width)


def lin2lin(fragment, width, newwidth):
    data = _array(fragment, width)
    if newwidth not in _CODES:
        raise error("Size should be 1, 2 or 4")
    if newwidth == width:
        return bytes(fragment)
    out = []
    for value in data:
        if newwidth > width:
            value = value << ((newwidth - width) * 8)
        else:
            value = value >> ((width - newwidth) * 8)
        out.append(_clip(value, newwidth))
    return _pack(out, newwidth)


def ratecv(fragment, width, nchannels, inrate, outrate, state, weightA=1, weightB=0):
    if nchannels < 1:
        raise error("# of channels should be >= 1")
    if width not in _CODES:
        raise error("Size should be 1, 2 or 4")
    if weightA < 1 or weightB < 0:
        raise error("weightA should be >= 1, weightB should be >= 0")
    data = _array(fragment, width)
    if len(data) % nchannels:
        raise error("not a whole number of frames")
    if inrate <= 0 or outrate <= 0:
        raise error("sampling rate not > 0")
    a, b = inrate, outrate
    while b:
        a, b = b, a % b
    inrate //= a
    outrate //= a
    a, b = weightA, weightB
    while b:
        a, b = b, a % b
    weightA //= a
    weightB //= a
    if state is None:
        d = -outrate
        prev = [0] * nchannels
        cur = [0] * nchannels
    else:
        d, samples = state
        if len(samples) != nchannels:
            raise error("illegal state argument")
        prev = [int(s[0]) for s in samples]
        cur = [int(s[1]) for s in samples]
    frames = len(data) // nchannels
    position = 0
    out = []
    weight = weightA + weightB
    while True:
        while d < 0:
            if position == frames:
                new_state = (d, tuple((prev[c], cur[c]) for c in range(nchannels)))
                return _pack(out, width), new_state
            for c in range(nchannels):
                prev[c] = cur[c]
                cur[c] = data[position * nchannels + c]
                cur[c] = _trunc_div(weightA * cur[c] + weightB * prev[c], weight)
            position += 1
            d += outrate
        while d >= 0:
            for c in range(nchannels):
                out.append(_trunc_div(prev[c] * d + cur[c] * (outrate - d), outrate))
            d -= inrate
