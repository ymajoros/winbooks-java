package be.valuya.winbooks  ;

import com4j.*;

@IID("{77E08644-03C1-4EBE-8FD5-6600D3180798}")
public interface _SqlError extends Com4jObject {
  // Methods:
  /**
   * <p>
   * Property Get only . return a description of the last error.
   * </p>
   * <p>
   * Getter method for the COM property "Description"
   * </p>
   * @return  Returns a value of type java.lang.String
   */

  @DISPID(1745027072) //= 0x68030000. The runtime will prefer the VTID if present
  @VTID(7)
  java.lang.String description();


  /**
   * <p>
   * initialize error handler
   * </p>
   */

  @DISPID(1610809345) //= 0x60030001. The runtime will prefer the VTID if present
  @VTID(8)
  void clear();


  // Properties:
}
