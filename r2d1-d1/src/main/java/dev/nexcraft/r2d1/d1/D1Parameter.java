package dev.nexcraft.r2d1.d1;

import java.util.Objects;

/** Preserves the logical SQL parameter type together with its REST wire representation. */
sealed interface D1Parameter
    permits D1Parameter.IntegerParameter, D1Parameter.RealParameter, D1Parameter.TextParameter {

  String wireValue();

  record TextParameter(String value) implements D1Parameter {

    public TextParameter {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public String wireValue() {
      return value;
    }
  }

  record IntegerParameter(long value) implements D1Parameter {

    @Override
    public String wireValue() {
      return Long.toString(value);
    }
  }

  record RealParameter(double value) implements D1Parameter {

    public RealParameter {
      if (!Double.isFinite(value)) {
        throw new IllegalArgumentException("double index value must be finite");
      }
    }

    @Override
    public String wireValue() {
      return Double.toString(value);
    }
  }
}
