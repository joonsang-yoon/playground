# LowMask

`LowMask` returns a fixed-width `UInt` whose set bits form one contiguous low-order run starting at bit $0$.

Use it when hardware has reduced a wide signal into groups and needs a mask for the reduced groups below a runtime boundary. The helper is threshold-space logic: `in`, `topBound`, and `bottomBound` are all expressed in the same control-value space. The bounds are elaboration-time thresholds in that space, not output bit indexes.

## Contract

```scala
object LowMask {
  def apply(in: UInt, topBound: BigInt, bottomBound: BigInt): UInt
}
```

| Item | Contract |
| --- | --- |
| `in` | runtime unsigned control value |
| `topBound` | elaboration-time threshold in `in`'s value space |
| `bottomBound` | elaboration-time threshold in `in`'s value space |
| Output width | $w = abs(topBound - bottomBound)$ bits |
| Required relation | `topBound != bottomBound` |

The implementation enforces only `topBound != bottomBound`. The documented operating domain is:

```scala
val numInVals = BigInt(1) << in.getWidth
```

```scala
0 <= min(topBound, bottomBound)
max(topBound, bottomBound) <= numInVals
```

All in-tree callers stay inside this domain. Values outside it may elaborate, but generated slices and replicated regions no longer describe the intended threshold interval.

Since `topBound != bottomBound`, the documented output width is at least $1$. The width is always an elaboration-time constant.

Use `LowMask` when:

- `in` is a runtime threshold or distance in a known value space
- the output should be a low-order run of set bits with fixed width
- values before and after the threshold interval should saturate

Do not use it as an arbitrary bit-index mask. Output bit indexes are derived only after the active-bit count is computed.

## Mental Model

Read a call as a threshold comparison over the value carried by `in`.

```scala
LowMask(in, topBound, bottomBound)
```

At each call site, check four things:

1. What value space does `in` use?
2. Are both bounds expressed in that same value space?
3. Is the output width meant to be $abs(topBound - bottomBound)$?
4. Should the mask grow with `in` or shrink with `in`?

The common mistake is treating `topBound` and `bottomBound` as output bit indexes. They are thresholds. The output indexes are assigned only after the active low-bit count is known.

| Bound order | Before interval | Inside interval | After interval |
| --- | --- | --- | --- |
| `topBound > bottomBound` | all zeros | grows by one bit per threshold step | all ones |
| `topBound < bottomBound` | all ones | shrinks by one bit per threshold step | all zeros |

Call-site audit:

- keep bounds in the same reduced scale as `in`
- use ascending bounds when the mask should grow with `in`
- use descending bounds when the mask should shrink with `in`
- confirm downstream logic expects a low-order run with no holes

## Semantic Model

Let $w = abs(topBound - bottomBound)$. `LowMask` computes an active-bit count, clamps it to $[0, w]$, and returns a mask with exactly that many low bits set.

| Bound order | Active-bit count |
| --- | --- |
| `topBound > bottomBound` | $ones = clamp(in - bottomBound, 0, w)$ |
| `topBound < bottomBound` | $ones = clamp(bottomBound - in, 0, w)$ |

The result is $0$ when $ones = 0$ and $2^{ones} - 1$ otherwise. The clamp is the semantic behavior, not a separate hardware API: values before the interval map to no active bits, values after the interval map to all active bits, and values inside the interval map to the distance from the active threshold.

Equivalent software model:

```scala
def lowMaskModel(in: BigInt, topBound: BigInt, bottomBound: BigInt): BigInt = {
  require(topBound != bottomBound)

  val width = (topBound - bottomBound).abs
  val ones =
    if (topBound > bottomBound) {
      (in - bottomBound).max(0).min(width)
    } else {
      (bottomBound - in).max(0).min(width)
    }

  if (ones == 0) BigInt(0) else (BigInt(1) << ones.toInt) - 1
}
```

Useful invariants:

- `PopCount(out)` equals the clamped active-bit count.
- The output has no holes.
- The output width is fixed at elaboration time.
- The output is nondecreasing with `in` when `topBound > bottomBound`.
- The output is nonincreasing with `in` when `topBound < bottomBound`.

## Ascending Ramp

When `topBound > bottomBound`, the active low-order region grows as `in` increases.

| Input range | Output population |
| --- | --- |
| `in <= bottomBound` | $0$ low bits set |
| `bottomBound < in < topBound` | $in - bottomBound$ low bits set |
| `in >= topBound` | $w$ low bits set |

For $0 <= i < w$:

```scala
out(i) == 1  iff  bottomBound + i < in
```

Example:

```scala
LowMask(in, topBound = 5, bottomBound = 2)
```

This returns a $3$-bit result. As `in` reaches $3$, $4$, and $5$, output bits $0$, $1$, and $2$ turn on.

| `in` | `out` |
| ---: | :--- |
| 0 | `000` |
| 1 | `000` |
| 2 | `000` |
| 3 | `001` |
| 4 | `011` |
| 5 | `111` |
| 6 | `111` |

## Descending Ramp

When `topBound < bottomBound`, the active low-order region shrinks as `in` increases.

| Input range | Output population |
| --- | --- |
| `in <= topBound` | $w$ low bits set |
| `topBound < in < bottomBound` | $bottomBound - in$ low bits set |
| `in >= bottomBound` | $0$ low bits set |

For $0 <= i < w$:

```scala
out(i) == 1  iff  in < bottomBound - i
```

Example:

```scala
LowMask(in, topBound = 2, bottomBound = 5)
```

This returns a $3$-bit result. As `in` reaches $3$, $4$, and $5$, output bits $2$, $1$, and $0$ turn off.

| `in` | `out` |
| ---: | :--- |
| 0 | `111` |
| 1 | `111` |
| 2 | `111` |
| 3 | `011` |
| 4 | `001` |
| 5 | `000` |
| 6 | `000` |

## Implementation Strategy

The source keeps the semantic model separate from the code-generation strategy.

| Case | Strategy |
| --- | --- |
| `topBound < bottomBound` | mirror the input domain and reuse the ascending case |
| `(1 << in.getWidth) <= 64` | build from a signed constant, arithmetic shift, and slice |
| larger input domains | split recursively on the MSB to avoid a very wide dynamic shifter |

For the descending case, if:

```scala
val numInVals = BigInt(1) << in.getWidth
```

then:

```scala
LowMask(in, topBound, bottomBound) ==
  LowMask(~in, numInVals - 1 - topBound, numInVals - 1 - bottomBound)
```

The large-domain path splits the input range at `mid = numInVals >> 1`, recurses on the low bits of `in`, and stitches the result with `Mux`, `Cat`, and replicated `1`s. The $64$-value cutover is an implementation choice for simulation performance, not a semantic boundary.

These strategies must preserve the semantic model above. When reviewing implementation changes, compare against `lowMaskModel` rather than against a particular shifter or recursive shape.

## In-Tree Usage

`LowMask` appears in sticky, alignment, normalization, and rounding logic. The common pattern is:

1. Reduce a wide signal into $2$-bit or $4$-bit groups.
2. Reduce the runtime distance into that same group scale.
3. Use `LowMask` to select low reduced groups below a runtime boundary.

Sticky-style alignment mask in `AddRecFN`:

```scala
OrReduceBy4(Cat(far_sigSmaller, 0.U(2.W))) &
  LowMask(alignDist(alignDistWidth - 1, 2), (sigWidth + 5) >> 2, 0)
```

Fused multiply-add alignment mask:

```scala
OrReduceBy4(
  Cat(
    rawC.sig(sigWidth - 1 - ((sigSumWidth - 1) & 3), 0),
    0.U(((sigSumWidth - sigWidth - 1) & 3).W)
  )
) & LowMask(
  cAlignDist(log2Ceil(sigSumWidth) - 1, 2),
  (sigSumWidth - 1) >> 2,
  (sigSumWidth - sigWidth - 1) >> 2
)
```

Descending ramps appear in fused multiply-add normalization paths:

```scala
LowMask(
  io.in.mulAddMetadata.cDom_cAlignDist(log2Ceil(sigWidth + 1) - 1, 2),
  0,
  sigWidth >> 2
)

LowMask(
  notCDom_normDistReduced2(log2Ceil(sigWidth + 2) - 1, 1),
  0,
  (sigWidth + 2) >> 2
)
```

They also appear in rounding paths:

```scala
Cat(
  LowMask(
    sAdjustedExp(outExpWidth, 0),
    outMinNormExp - outSigWidth - 1,
    outMinNormExp
  ) | doShiftSigDown1,
  3.U(2.W)
)
```
