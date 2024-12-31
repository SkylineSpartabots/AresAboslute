package frc.robot.Subsystems.Vision;

import edu.wpi.first.wpilibj2.command.SubsystemBase;
import edu.wpi.first.networktables.*;
import frc.lib.vision.LimeLight;
import frc.robot.LimelightHelpers;
import frc.robot.Subsystems.CommandSwerveDrivetrain.Drivetrain;
import org.littletonrobotics.junction.AutoLogOutput;

import java.util.HashMap;
import java.util.Map;

public class LimelightSubsystem extends SubsystemBase {
    private static LimelightSubsystem instance;
    
    private NetworkTable nt;
    // Two ways to get data: the Limelight Helpers class (preferred) and the limeLight object which provides access to a library someone else made and i thought would be useful
    private LimeLight limeLight; // lib
    private String limelightName = "limelight"; // required for all LimelightHelpers method calls - or pass a blank string if the name is the default (limelight

    private final Drivetrain drivetrain;


    public static LimelightSubsystem getInstance() {
        if (instance == null) {
            instance = new LimelightSubsystem();
        }
        return instance;
    }

    public enum LedMode {
        pipeline(0),   //0	use the LED Mode set in the current pipeline
        forceOff(1),   //1	force off
        forceBlink(2), //2	force blink
        forceOn(3);    //3	force on 

        private static final Map<Double, LedMode> MY_MAP = new HashMap<Double, LedMode>();

        static {
            for (LedMode LedMode : values()) {
                MY_MAP.put(LedMode.getValue(), LedMode);
            }
        }

        private double value;

        LedMode(double value) {
            this.value = value;
        }

        public double getValue() {
            return value;
        }

        public static LedMode getByValue(double value) {
            return MY_MAP.get(value);
        }

        public String toString() {
            return name();
        }

    }

    public enum Pipeline{
        Coral_Detector(0),
        Color_Detection(1);
        private static final Map<Integer, Pipeline> MY_MAP = new HashMap<Integer, Pipeline>();

        static {
            for (Pipeline Pipeline : values()) {
                MY_MAP.put(Pipeline.getIndex(), Pipeline);
            }
        }

        private int index;

        Pipeline(int index) {
            this.index = index;
        }

        public int getIndex() {
            return index;
        }

        public static Pipeline getByIndex(int index) {
            return MY_MAP.get(index);
        }

        public String toString() {
            return name();
        }

    }
    private LimelightSubsystem() {
        limeLight = new LimeLight(); // init lib
        nt = NetworkTableInstance.getDefault().getTable(limelightName);
        drivetrain = Drivetrain.getInstance();
    }
    @AutoLogOutput(key = "Limelight/Connected")
    public boolean isConnected() {
        return NetworkTableInstance.getDefault().getTable(limelightName).containsKey("ledMode");
    }

     @AutoLogOutput(key = "Limelight/hasTarget")
     public boolean hasTarget() {
          return LimelightHelpers.getTV(limelightName);
     }
     
    @AutoLogOutput(key = "Limelight/TargetArea")
    public double getTargetArea() {
         return LimelightHelpers.getTA(limelightName);
    }
    @AutoLogOutput(key = "Limelight/xOffset")
    public double getXOffset() {
         return LimelightHelpers.getTX(limelightName);
    }
    @AutoLogOutput(key = "Limelight/yOffset")
    public double getYOffset() {
         return LimelightHelpers.getTY(limelightName);
    }
    @AutoLogOutput(key = "Limelight/LEDMode")
    public LedMode getLEDMode(){
        return LedMode.getByValue( nt.getEntry("ledMode").getInteger(-1));
    }
    // note, cone cube etc
    @AutoLogOutput(key = "Limelight/DetectorClass")
    public String getDetectorClass(){
        return LimelightHelpers.getDetectorClass(limelightName);
    }
    
    @AutoLogOutput(key = "Limelight/PipelineLatency")
    public double getPipelineLatency(){
        return LimelightHelpers.getLatency_Pipeline(limelightName);
    }

    @AutoLogOutput(key = "Limelight/PipelineIndex")
    public int getCurrentPipelineIndex(){
        return (int) LimelightHelpers.getCurrentPipelineIndex(limelightName);
    }
    
    public void setLEDMode(LedMode mode) {
        nt.getEntry("ledMode").setNumber(mode.getValue());
    }

    public void setPipeline(Pipeline pipeline){
        LimelightHelpers.setPipelineIndex(limelightName, pipeline.getIndex());
    }
    
    
    @Override
    public void periodic() {
        //read values periodically

        // System.out.println(nt);

        //post to smart dashboard periodically

//       Logger.recordOutput("Limelight/xOffset", getXOffset());
//       Logger.recordOutput("Limelight/yOffset", getYOffset());
//    //    Logger.recordOutput("Limelight/Distance", getDistance());
//    //    SmartDashboard.putNumber("LimelightX", getXOffset());
//       SmartDashboard.putNumber("LimelightY", getYOffset());
//        SmartDashboard.putNumber("Limelight Distance", getDistance());

//        if(DriverStation.isTeleop()){
//
//            if(Math.abs(getXOffset()) < 20.0  && hasTarget()){      //&& Math.abs(getXOffset()) > 5.0
//                double x = 8.23 - (getDistance() *
//                        Math.cos(Math.toRadians(drivetrain.getGyroscopeRotation().getDegrees() + 180
//                                - getXOffset())));
//                double y = 4.165 - (getDistance() *
//                        Math.sin(Math.toRadians(drivetrain.getGyroscopeRotation().getDegrees() + 180
//                                - getXOffset())));//plus or minus xoffset???
//
//                drivetrain.resetOdometryFromPosition(x,y);
//            }
//        }
    }

    // @AutoLogOutput(key = "Limelight/Distance")
    // public double getDistance() {
    //     double limelightMountAngleDegrees = 27.0;
    //     double limelightLensHeightInches = 35;
    //     double goalHeightInches = 104.0;
    //     double angleToGoalDegrees = limelightMountAngleDegrees + getYOffset();

    //     //calculate distance
    //     double distanceFromLimelightToGoalInches =
    //             ((goalHeightInches - limelightLensHeightInches) / (Math.tan(Math.toRadians(angleToGoalDegrees))))
    //                     + 12 + 24;
    //     return distanceFromLimelightToGoalInches * 0.0254;
    // }
}