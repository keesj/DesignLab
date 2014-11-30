/*
 Gadget Factory
 DesignLab New Project
 To learn more about using DesignLab please visit http://learn.gadgetfactory.net

 Tutorials:
   http://gadgetfactory.net/learn/2013/10/29/papilio-schematic-library-getting-started/
  
 Related library documentation:

 Hardware:
   
 Special Notes:

 created 2014
 by Jack Gassett
 http://www.gadgetfactory.net
 
 This example code is in the public domain.
 */
int led = 13;
 
void setup() {
  // put your setup code here, to run once:
  
  pinMode(0, OUTPUT);   
  
}

void loop() {
  digitalWrite(led, HIGH);   // turn the LED on (HIGH is the voltage level)
  delay(1000);               // wait for a second
  digitalWrite(led, LOW);    // turn the LED off by making the voltage LOW
  delay(1000);               // wait for a second
}