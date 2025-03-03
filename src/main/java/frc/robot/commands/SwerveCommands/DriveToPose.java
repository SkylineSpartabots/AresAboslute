package frc.robot.commands.SwerveCommands;

import java.awt.Robot;
import java.util.function.Supplier;

import org.littletonrobotics.junction.Logger;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.controller.ProfiledPIDController;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.wpilibj2.command.Command;
import frc.lib.Interpolating.Geometry.IChassisSpeeds;
import frc.robot.Constants;
import frc.robot.Constants.FieldConstants.ReefConstants.ReefPoleSide;
import frc.robot.RobotState.RobotState;
import frc.robot.Subsystems.CommandSwerveDrivetrain.CommandSwerveDrivetrain;
import frc.robot.Subsystems.Elevator.ElevatorState;
import frc.robot.commands.Elevator.SetElevator;

/**
 * Drives to a specified pose.
 */
public class DriveToPose extends Command {
    private final ProfiledPIDController driveController = new ProfiledPIDController(
            4.5, 0.13, 0.01, new TrapezoidProfile.Constraints(Constants.MaxSpeed + 2, Constants.MaxAcceleration), 0.02);
    private final ProfiledPIDController thetaController = new ProfiledPIDController(
            5, 1.5, 0, new TrapezoidProfile.Constraints(Constants.MaxAngularVelocity + 2*Math.PI, Constants.MaxAngularRate), 0.02);

    private CommandSwerveDrivetrain s_Swerve;

    private Supplier<ElevatorState> elevatorLevel;
    private Double elevatorGoalPos = null;

    private Pose2d targetPose;
    private RobotState robotState;
    private Translation2d lastSetpointTranslation;
    private double driveErrorAbs;
    private double thetaErrorAbs;
    private double ffMinRadius = 0.2, ffMaxRadius = 1.2, elevatorDistanceThreshold = 1;

    public DriveToPose(Supplier<Pose2d> targetPose) {
        this.s_Swerve = CommandSwerveDrivetrain.getInstance();
        this.robotState = RobotState.getInstance();
        this.targetPose = targetPose.get();

        addRequirements(s_Swerve);
        thetaController.enableContinuousInput(-Math.PI, Math.PI);
    }
    
    public DriveToPose(ReefPoleSide side) {
        this.s_Swerve = CommandSwerveDrivetrain.getInstance();
        this.robotState = RobotState.getInstance();
        this.targetPose = side.getClosestPoint(s_Swerve.getPose()); // Point A

        addRequirements(s_Swerve);
        thetaController.enableContinuousInput(-Math.PI, Math.PI);       
    }

    public DriveToPose(ReefPoleSide side, Supplier<ElevatorState> elevatorLevel) {
        this.s_Swerve = CommandSwerveDrivetrain.getInstance();
        this.robotState = RobotState.getInstance();
        this.targetPose = side.getClosestPoint(s_Swerve.getPose());
        this.elevatorLevel = elevatorLevel;

        addRequirements(s_Swerve);
        thetaController.enableContinuousInput(-Math.PI, Math.PI);       
    }

    @Override
    public void initialize() {
        if(elevatorLevel != null)
                elevatorGoalPos = elevatorLevel.get().getEncoderPosition();

        Pose2d currentPose = robotState.getCurrentPose2d();
        IChassisSpeeds speeds = robotState.getLatestFilteredVelocity();
        driveController.reset(
                currentPose.getTranslation().getDistance(targetPose.getTranslation()),
                Math.min(
                        0.0,
                        -new Translation2d(speeds.getVx(),speeds.getVy())
                                .rotateBy(
                                        targetPose
                                                .getTranslation()
                                                .minus(robotState.getCurrentPose2d().getTranslation())
                                                .getAngle()
                                                .unaryMinus())
                                .getX())); // Distance between current and target pose

        thetaController.reset(s_Swerve.getHeading(),
                robotState.getLatestFilteredVelocity().getOmega());
        
        thetaController.setTolerance(0.04);
                
        lastSetpointTranslation = robotState.getCurrentPose2d().getTranslation();
    }

    @Override
    public void execute() {
        Pose2d currentPose = robotState.getCurrentPose2d();

        double currentDistance = currentPose.getTranslation().getDistance(targetPose.getTranslation()); //error between poses

        double ffScaler = MathUtil.clamp(
                (currentDistance - ffMinRadius) / (ffMaxRadius - ffMinRadius),
                0.0,
                1.0);

        driveErrorAbs = currentDistance;
        
        driveController.reset(
                lastSetpointTranslation.getDistance(targetPose.getTranslation()),
                driveController.getSetpoint().velocity);
        double driveVelocityScalar = driveController.getSetpoint().velocity * ffScaler
                + driveController.calculate(driveErrorAbs, 0.0);

        if (currentDistance < driveController.getPositionTolerance())
            driveVelocityScalar = 0.0;
            lastSetpointTranslation = new Pose2d(
                targetPose.getTranslation(),
                currentPose.getTranslation().minus(targetPose.getTranslation()).getAngle())
                .transformBy(
                        new Transform2d(new Translation2d(driveController.getSetpoint().position, 0.0), new Rotation2d()))
                .getTranslation();

        System.out.println(driveErrorAbs);
        //Start bringing up elevator if included in command
        if(elevatorGoalPos != null && driveErrorAbs < elevatorDistanceThreshold) {
                System.out.println("schedudelelellelelle: " + elevatorLevel.get().name());
                new SetElevator(elevatorGoalPos).schedule();
                elevatorGoalPos = null;
        }

        // Calculate theta speed
        double thetaVelocity = thetaController.getSetpoint().velocity * ffScaler
                + thetaController.calculate(
                        currentPose.getRotation().getRadians(), targetPose.getRotation().getRadians());
        thetaErrorAbs = Math.abs(currentPose.getRotation().minus(targetPose.getRotation()).getRadians());
        if (thetaErrorAbs < thetaController.getPositionTolerance())
            thetaVelocity = 0.0;

        // Command speeds
        var driveVelocity = new Pose2d(new Translation2d(), currentPose.getTranslation().minus(targetPose.getTranslation()).getAngle())
                .transformBy(new Transform2d(new Translation2d(driveVelocityScalar, 0.0), new Rotation2d()))
                .getTranslation();
                s_Swerve.applyFieldSpeeds(new ChassisSpeeds(driveVelocity.getX(), driveVelocity.getY(), thetaVelocity));

        //prints
        System.out.println("Theta error: " + thetaErrorAbs);
        System.out.println("drive error: " + driveErrorAbs);
        // System.out.println("Position Drivetrain error: " + driveController.getPositionError());
        // System.out.println("Drivetrain error: " + driveController.getPositionError());
        // System.out.println("Position Theta error: " + thetaController.getPositionError());
        // System.out.println("Drive velocity: " + driveVelocityScalar);
    }

    @Override
    public void end(boolean interrupted) {
        s_Swerve.applyFieldSpeeds(new ChassisSpeeds());
    }

    @Override
    public boolean isFinished() {
        return targetPose.equals(null) || (driveController.atGoal() && thetaController.atGoal());
    }
}