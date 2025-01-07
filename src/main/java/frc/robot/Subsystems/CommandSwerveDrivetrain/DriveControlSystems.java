package frc.robot.Subsystems.CommandSwerveDrivetrain;

import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.mechanisms.swerve.LegacySwerveModule.DriveRequestType;
import com.ctre.phoenix6.swerve.SwerveRequest;
import com.ctre.phoenix6.swerve.SwerveModule;

import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.filter.SlewRateLimiter;
import edu.wpi.first.math.interpolation.Interpolator;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj.Joystick;
import edu.wpi.first.wpilibj.shuffleboard.Shuffleboard;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import frc.robot.Constants;
import frc.robot.RobotContainer;
import frc.robot.Subsystems.Intake;
import frc.robot.generated.TunerConstants;

import javax.xml.stream.events.DTD;

import org.littletonrobotics.junction.Logger;

// import frc.robot.RobotState.RobotState;

public class DriveControlSystems {

    private boolean slipControlOn = false;
    private boolean headingControl = false;
    private boolean shooterMode = false;
    private boolean aligning = false;
    private boolean inputting = false;
    private double lastHeading = 0;

    // Can tune
    private double deadbandFactor = 0.8; // higher is more linear joystick controls


    Drivetrain drivetrain;
    // RobotState robotState;

    PIDController pidHeading = new PIDController(0, 0, 0);

    private static DriveControlSystems controlSystems;

    public static DriveControlSystems getInstance(){
        if(controlSystems == null){
            controlSystems = new DriveControlSystems();  
        }
        return controlSystems;
    }

    public DriveControlSystems() {  
        // robotState = RobotState.getInstance();
        drivetrain = Drivetrain.getInstance();
    }

    //interface with modules
    public SwerveModule getModule(int index) {
      return drivetrain.getModule(index);
    }   

     // =======---===[ ⚙ Joystick processing ]===---========
    public SwerveRequest drive(double driverLY, double driverLX, double driverRX){
        driverLX = scaledDeadBand(driverLX) * Constants.MaxSpeed;
        driverLY = scaledDeadBand(driverLY) * Constants.MaxSpeed;
        driverRX = scaledDeadBand(driverRX) * Constants.MaxAngularRate;

        inputting = driverLX > 0.1 || driverLY > 0.1 || driverRX > 0.1;

        SmartDashboard.putNumber("requested velocity x", driverLX);
        SmartDashboard.putNumber("requested velocity y", driverLY);
        Logger.recordOutput("JoystickProcessing/RequestedX", driverLX);
        Logger.recordOutput("JoystickProcessing/RequestedY", driverLY);

        ChassisSpeeds speeds = new ChassisSpeeds(driverLY, driverLX, driverRX);

        double[][] wheelFeedFwX = calculateFeedforward();
        
        return new SwerveRequest.ApplyFieldSpeeds()
        .withSpeeds(speeds)
        .withWheelForceFeedforwardsX(wheelFeedFwX[0])
        .withWheelForceFeedforwardsY(wheelFeedFwX[1])
        .withDriveRequestType(com.ctre.phoenix6.swerve.SwerveModule.DriveRequestType.OpenLoopVoltage)
        .withDesaturateWheelSpeeds(true);
    }

    private double[] previousVelocities = new double[4]; // To store previous velocity for each module

    public double[][] calculateFeedforward() {
        double[][] wheelFeedFwX = new double[4][4];
        //TODO tune
        double Kv = 0.1;  // velocity gain
        double Ka = 0.1;  // acceleration gain
        double Kf = 0.1;  // friction gain

        for (int i = 0; i < 4; i++) {
            double currentVelocity = getModule(i).getCurrentState().speedMetersPerSecond;
            double angleComponent = Math.cos(getModule(i).getCurrentState().angle.getRadians());

            double X = Kv * currentVelocity * angleComponent
            + Ka * ((currentVelocity - previousVelocities[i]) / Constants.dt) * angleComponent //acceleration
            + Kf;

            wheelFeedFwX[0][i] = X;
        }

        for (int i = 0; i < 4; i++) {
            double currentVelocity = getModule(i).getCurrentState().speedMetersPerSecond;
            double angleComponent = Math.sin(getModule(i).getCurrentState().angle.getRadians());

            double Y = Kv * currentVelocity * angleComponent
            + Ka * ((currentVelocity - previousVelocities[i]) / Constants.dt) * angleComponent //acceleration
            + Kf;

            previousVelocities[i] = currentVelocity;

            wheelFeedFwX[1][i] = Y;
        }


        return wheelFeedFwX;
    }

    public double scaledDeadBand(double input) {
        if(Math.abs(input) < Constants.stickDeadband) 
            return 0;
        else
            return (deadbandFactor * Math.pow(input, 3)) + (1 - deadbandFactor) * input;
    }

    public Boolean pollInput() {
        return inputting;
    }

    // =======---===[ ⚙ Heading control ]===---========
    // public double headingControl(double driverRX){ //TODO tune high and low PID values
    //     if (!pidHeading.atSetpoint()) {
    //         double velocity = drivetrain.robotWheelVelocity();
    //         updateGains(velocity);
            
    //         // driverRX = pidHeading.calculate(robotState.robotYaw(), lastHeading);
    //         SmartDashboard.putBoolean("headingON", true);
//    Logger.recordOutput("HeadingControl/Active", true);

    //     } else {
    //         SmartDashboard.putBoolean("headingON", false);
    //         SmartDashboard.putNumber("lastHeading", lastHeading);
//    Logger.recordOutput("HeadingControl/Active", false);
//    Logger.recordOutput("HeadingControl/LastHeading", lastHeading);
    //     }

    //     return driverRX;
    // } 
    // TODO fix this later bruh

    public void updateGains(double velocity) {
        double speedRatio = Math.abs(Constants.MaxSpeed/velocity); //velocity is from wheels so could be off
        speedRatio = Math.max(0, Math.min(1, speedRatio));
        //clamp between 0 and 1

        //can tune
        pidHeading.setPID(
            interpolate(Constants.robotPIDs.HeadingControlPID.lowP, Constants.robotPIDs.HeadingControlPID.highP, speedRatio), // P
            0, // I (we do not need I)
            interpolate(Constants.robotPIDs.HeadingControlPID.lowD, Constants.robotPIDs.HeadingControlPID.highD, speedRatio) // D
            ); 
    }

    public double interpolate(double lower, double upper, double scale) {
        return Interpolator.forDouble().interpolate(lower, upper, scale);
    }

    // =======---===[ ⚙ Slip Control ]===---========

    //useless bc our IMU gives poop values

    public Double[] slipControl(double currentVelocity) { 

    Double[] outputs = new Double[4]; // reset to null every call
        for (int i = 0; i < 4; i++) {  //4 is module count but i dont want to make a getter
        
        //gets the ratio between what the encoders think our velocity is and the real velocity
        double slipRatio;
        if(currentVelocity == 0) { slipRatio = 1; } else {
            slipRatio = ((getModule(i).getCurrentState().speedMetersPerSecond) / currentVelocity); 
        }
        SmartDashboard.putNumber("Module " + i + " slipratio", slipRatio);
        Logger.recordOutput("SwerveModules/SlipRatios/Module " + i , slipRatio);
        //if over the upper or lower threshold save the value
        if (slipRatio > (Constants.slipThreshold + 1) || slipRatio < (1 - Constants.slipThreshold)) {
            outputs[i] = slipRatio;
        }
    } 

    return outputs;
    } // runs periodically as a default command

    public void slipCorrection(Double[] inputs) {
        // divides by slip factor, more aggressive if far above slip threshold 
        for (int i = 0; i < 4; i++) { //4 is module count but i dont want to make a getter

            if (inputs[i] != null) {
                TalonFX module = (TalonFX) drivetrain.getModule(i).getDriveMotor();
                
                module.set(module.get() *
                 (1 + (Math.signum(inputs[i] - 1)) * (inputs[i] - Constants.slipThreshold)) / Constants.slipFactor);
                //https://www.desmos.com/calculator/afe5omf92p how slipfactor changes slip aggression

                SmartDashboard.putBoolean("slipON", true);
                Logger.recordOutput("SlipControl/Active", true);
            }  else {
                SmartDashboard.putBoolean("slipON", false);
                Logger.recordOutput("SlipControl/Active", false);
            } 
            
        }
    }

    //toggling ----------------------------------------------------------------
    public void setLastHeading() {
        lastHeading = drivetrain.getPose().getRotation().getRadians(); 
    }

    public void toggleHeadingControl() {
        headingControl = !headingControl;
    }

    public void toggleAlignment() {
        aligning = !aligning;   
    }

    public void toggleSlipControl() {
        slipControlOn = !slipControlOn;
    }

    public void setHeadingTolerance() {
        pidHeading.setTolerance(0.1745); // 10 degrees in radians
    }


}
