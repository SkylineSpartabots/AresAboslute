package frc.robot.RobotState;

import java.util.Optional;

// import org.littletonrobotics.junction.Logger;

import com.ctre.phoenix6.hardware.Pigeon2;

import edu.wpi.first.math.MatBuilder;
import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.Nat;
import edu.wpi.first.math.StateSpaceUtil;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.Vector;
import edu.wpi.first.math.estimator.UnscentedKalmanFilter;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N2;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import frc.lib.AccelerationIntegrator;
import frc.lib.Interpolating.Geometry.IPose2d;
import frc.lib.Interpolating.Geometry.ITranslation2d;
import frc.lib.Interpolating.Geometry.ITwist2d;
import frc.lib.Interpolating.Interpolable;
import frc.lib.Interpolating.IDouble;
import frc.lib.Interpolating.InterpolatingTreeMap;
import frc.lib.VisionOutput;
import frc.robot.Constants;
import frc.robot.Subsystems.CommandSwerveDrivetrain.CommandSwerveDrivetrain;

public class RobotState { //will estimate pose with odometry and correct drift with vision
    private static RobotState instance;

    public static RobotState getInstance() {
        if (instance == null) {
            instance = new RobotState();
        }
        return instance;
    }

    private static AccelerationIntegrator accelIntegrator = new AccelerationIntegrator();
    
    CommandSwerveDrivetrain drivetrain;
    Pigeon2 pigeon;

    private InterpolatingTreeMap<IDouble, IPose2d> odometryPoses;
	private InterpolatingTreeMap<IDouble, ITranslation2d> filteredPoses;
    private InterpolatingTreeMap<IDouble, ITwist2d> robotIMUVelocity;
    private InterpolatingTreeMap<IDouble, ITwist2d> robotOdomVelocity;
    private InterpolatingTreeMap<IDouble, ITwist2d> robotAccelerations;
    private InterpolatingTreeMap<IDouble, IDouble> robotAngularVelocity;
    private InterpolatingTreeMap<IDouble, ITwist2d> filteredRobotVelocities;

    Matrix<N2, N2> initialCovariance = MatBuilder.fill(Nat.N2(), Nat.N2(),
    0.03, 0.0,
    0.0, 0.03 );

    private UnscentedKalmanFilter<N2, N2, N2> UKF;

    private static final double dt = 0.020;
    private static final int observationSize = 50; //how many poses we keep our tree

	private Optional<ITranslation2d> initialFieldToOdo = Optional.empty();
    private Optional<Double> prevOdomTimestamp = Optional.empty();

	private boolean inAuto = false; //need to configure with auto but we dont have an auto yet (lol)

    public RobotState() {
        drivetrain = CommandSwerveDrivetrain.getInstance();
        pigeon = drivetrain.getPigeon2();  //getting the already constructed pigeon in swerve
        reset(0.02, IPose2d.identity(), ITwist2d.identity()); //init
    }

    public synchronized void visionUpdate(VisionOutput updatePose) {

        double timestamp = updatePose.timestampSeconds;

        ITwist2d filteredVelocity = getInterpolatedValue(filteredRobotVelocities, timestamp, ITwist2d.identity());

        double stdev = updatePose.getStandardDeviation();

        //calculate std of vision estimate for UKF
        Vector<N2> stdevs = VecBuilder.fill(Math.pow(stdev, 2), Math.pow(stdev, 2));

                UKF.correct(
                        VecBuilder.fill(filteredVelocity.getX(),filteredVelocity.getY()),
                        VecBuilder.fill(
                                updatePose.estimatedPose.getX(),
                                updatePose.estimatedPose.getY()),
                        StateSpaceUtil.makeCovarianceMatrix(Nat.N2(), stdevs));
                        
                filteredPoses.put(
                        new IDouble(timestamp),
                        new ITranslation2d(UKF.getXhat(0), UKF.getXhat(1)));
                        
        SmartDashboard.putNumber("Vision std dev", stdev);
    }



    //if you dont understand ask iggy
    public void odometryUpdate(Pose2d pose, double[] wheelVelocity, double timestamp) {

        updateSensors();

        if(prevOdomTimestamp.isEmpty()) {
            // First time initialization of state
            initKalman();
            Matrix<N2, N1> initialState = VecBuilder.fill(pose.getX(), pose.getY());
            UKF.setXhat(initialState);
        } else {
            ITwist2d robotVelocity = getIMURobotVelocity(timestamp);

            ITwist2d OdomVelocity = getInterpolatedValue(odometryPoses, prevOdomTimestamp.get(), IPose2d.identity())
                .getVelocityBetween(new IPose2d(pose), timestamp - prevOdomTimestamp.get());

                robotOdomVelocity.put(new IDouble(timestamp), OdomVelocity);
            
            //    .complimentaryFilter(robotVelocity, 0.1); Could reimplement this if our pigeon values improve 

            double robotVelocityMagnitude = robotVelocity.toMagnitude();
            IDouble robotAngularMagnitude = getInterpolatedValue(robotAngularVelocity, timestamp, new IDouble(0.0));
            ITwist2d robotAcceleration = getInterpolatedValue(robotAccelerations, timestamp, ITwist2d.identity());

            SmartDashboard.putNumber("Accel", robotAcceleration.toMagnitude());
            SmartDashboard.putNumber("velocity", robotVelocityMagnitude);

            if(robotVelocityMagnitude > 0) { //manually increase P (our predicted error in pos)
                Matrix<N2,N2> P = UKF.getP();

                //TODO numbers are arbitrary
                // components of our uncertainty
                double curvature = Math.min(0.03, (
                    Math.hypot(robotAcceleration.getX(), robotAcceleration.getY()) / //acceleration over velocity
                    (750 * (robotVelocityMagnitude + 1))
                    ));

                double angular = 0.0005 * (robotAngularMagnitude.value / Constants.MaxAngularRate);

                SmartDashboard.putNumber("Curvature", curvature);
                SmartDashboard.putNumber("Angular", angular);

                //Make sure we dont get a crazy low number
                double newP = P.get(0, 0) + Math.max(1e-8, (curvature + angular));

                P.set(0, 0, newP);
                P.set(1, 1, newP);
                UKF.setP(P);
            }

            //predict next state using our control input (velocity)
            try {
                UKF.predict(VecBuilder.fill(OdomVelocity.getX(), OdomVelocity.getY()), dt);
                filteredRobotVelocities.put(new IDouble(timestamp), OdomVelocity);
            } catch (Exception e) {
                DriverStation.reportError("QR Decomposition failed: ", e.getStackTrace());
            }
        }

        odometryPoses.put(new IDouble(timestamp), new IPose2d(pose.getX(),pose.getY(), pose.getRotation()));

        prevOdomTimestamp = Optional.of(timestamp);

        SmartDashboard.putNumber("P MATRIX ", UKF.getP().get(0, 0));
        SmartDashboard.putNumber("FILT X", UKF.getXhat(0));
        SmartDashboard.putNumber("FILT Y", UKF.getXhat(1));
        // SmartDashboard.putNumber("VelocityMagnitude", );
    }


    /*  ExtendedKalmanFilter​(Nat<States> states, Nat<Inputs> inputs, Nat<Outputs> outputs,
    BiFunction<Matrix<States,​N1>,​Matrix<Inputs,​N1>,​Matrix<States,​N1>> f,
    BiFunction<Matrix<States,​N1>,​Matrix<Inputs,​N1>,​Matrix<Outputs,​N1>> h,
    Matrix<States,​N1> stateStdDevs, Matrix<Outputs,​N1> measurementStdDevs,
    double dtSeconds) */
    public void initKalman() {

        Matrix<N2, N1> stateStdDevsQ = VecBuilder.fill(
            Math.pow(1e-15, 1), // Variance in position x
            Math.pow(1e-15, 1)  // Variance in position y
        );

        Matrix<N2, N1> measurementStdDevsR = VecBuilder.fill(
            Math.pow(0.03, 2), // Variance in measurement x
            Math.pow(0.03, 2)   // Variance in measurement y
        );
    
        // states - A Nat representing the number of states.
        // outputs - A Nat representing the number of outputs.
        // f - A vector-valued function of x and u that returns the derivative of the state vector.
        // h - A vector-valued function of x and u that returns the measurement vector.
        // stateStdDevs - Standard deviations of model states.
        // measurementStdDevs - Standard deviations of measurements.
        // nominalDtSeconds - Nominal discretization timestep.
        UKF = new UnscentedKalmanFilter<>(Nat.N2(), Nat.N2(),
        (x,u) -> u,
        (x,u) -> x,
        stateStdDevsQ,
        measurementStdDevsR,
        dt);

        // Set initial state and covariance
        UKF.setP(initialCovariance); 
        }

        public void reset(double time, IPose2d initial_Pose2d, ITwist2d initial_Twist2d) { //init the robot state
            odometryPoses = new InterpolatingTreeMap<>(observationSize);
            odometryPoses.put(new IDouble(time), initial_Pose2d);
            filteredPoses = new InterpolatingTreeMap<>(observationSize);
            filteredPoses.put(new IDouble(time), getInitialFieldToOdom());
            robotIMUVelocity = new InterpolatingTreeMap<>(observationSize);
            robotIMUVelocity.put(new IDouble(time), initial_Twist2d);    
            robotOdomVelocity = new InterpolatingTreeMap<>(observationSize);
            robotOdomVelocity.put(new IDouble(time), initial_Twist2d);          
            robotAccelerations = new InterpolatingTreeMap<>(observationSize);
            robotAccelerations.put(new IDouble(time), initial_Twist2d);
            robotAngularVelocity = new InterpolatingTreeMap<>(observationSize);
            robotAngularVelocity.put(new IDouble(time), new IDouble(0.0));
            filteredRobotVelocities = new InterpolatingTreeMap<>(observationSize);
            filteredRobotVelocities.put(new IDouble(time), initial_Twist2d);
        }

        public void resetUKF(IPose2d initial_Pose2d) {
            UKF.reset();
            UKF.setXhat(MatBuilder.fill(Nat.N2(), Nat.N1(), initial_Pose2d.getX(), initial_Pose2d.getY()));
            UKF.setP(initialCovariance); 
        }

        public synchronized ITranslation2d getInitialFieldToOdom() {
            if (initialFieldToOdo.isEmpty()) return ITranslation2d.identity();
            return initialFieldToOdo.get();
        }

        // =======---===[ ⚙ Tree map helpers ]===---========

        /**
         * Gets value from map at timestamp. Linearly interpolates between gaps.
         * @param map Map to look in
         * @param timestamp Timestamp to look up
         * @param identity Identity of the value
         * @return Interpolated value at timestep
         */
        public synchronized <T extends Interpolable<T>> T getInterpolatedValue(InterpolatingTreeMap<IDouble, T> map, Double timestamp, T identity) {

        if (map.isEmpty())
            return identity;

        if (timestamp == null) 
            return map.get(map.lastKey());

        // Interpolate for the given timestamp
        return map.getInterpolated(new IDouble(timestamp));
        }


        // /**
        //  * Gets interpolated odometry pose using predicted robot velocity from latest
        //  * odometry update.
        //  *
        //  * @param lookahead_time Scalar for predicted velocity.
        //  * @return Predcited odometry pose at lookahead time.
        //  */
        // public synchronized Pose2d getPredictedOdomToVehicle(double lookahead_time) {
		//     return getLatestOdomToVehicle()
		// 		.getValue()
		// 		.transformBy(Pose2d.exp(vehicle_velocity_predicted.scaled(lookahead_time)));
	    // } TODO lookahead for auto


        public synchronized ITranslation2d getLatestFilteredPose() {
		    return getInterpolatedValue(filteredPoses, filteredPoses.lastKey().value, ITranslation2d.identity());
	    }

        public synchronized ITwist2d getLatestRobotVelocity() {
		    return getLatestOdomRobotVelocity()
                .complimentaryFilter(getLatestIMURobotVelocity(), 0.2);
	    }

        public synchronized ITwist2d getLatestIMURobotVelocity() {
		    return getInterpolatedValue(robotIMUVelocity, robotIMUVelocity.lastKey().value, ITwist2d.identity()); 
	    }

        public synchronized ITwist2d getLatestOdomRobotVelocity() {
		    return getInterpolatedValue(robotOdomVelocity, robotOdomVelocity.lastKey().value, ITwist2d.identity());
	    }

        public synchronized ITwist2d getRobotVelocity(double timestamp) {
		    return getOdomRobotVelocity(timestamp)
                .complimentaryFilter(getIMURobotVelocity(timestamp), 0.2);
	    }

        public synchronized ITwist2d getIMURobotVelocity(double timestamp) {
		    return getInterpolatedValue(robotIMUVelocity, timestamp, ITwist2d.identity());
	    }

        public synchronized ITwist2d getOdomRobotVelocity(double timestamp) {
		    return getInterpolatedValue(robotOdomVelocity, timestamp, ITwist2d.identity());  
	    }
    
        /**
         * Gets signed velocity from integrated acceleration from filtered velocities
         *
         * @return double VelocityVector
         */
        public synchronized double robotVelocityVector() {
            ITwist2d robotVelocity = getLatestRobotVelocity();
            return Math.signum(Math.atan2(robotVelocity.getX(), robotVelocity.getY()))
             * Math.hypot(robotVelocity.getX(), robotVelocity.getY());
        }

        //// =======---===[ ⚙ Pigeon2.0  ]===---========

        public void updateSensors() {
            double[] newAccel = rawRobotAcceleration();
            double[] newAngularVelocity = robotAngularVelocityMagnitude();
            robotIMUVelocity.put(new IDouble(newAccel[2]), accelIntegrator.update(newAccel));
            robotAngularVelocity.put(new IDouble(newAngularVelocity[1]), new IDouble(newAngularVelocity[0]));
            robotAccelerations.put(new IDouble(newAccel[2]), new ITwist2d(newAccel[0], newAccel[1]));

            SmartDashboard.putNumber("raw Accel X", newAccel[0]);

            SmartDashboard.putNumber("raw Accel Y", newAccel[1]);
            // Logger.recordOutput("RobotState/raw Accel X", newAccel[0]);
            // Logger.recordOutput("RobotState/raw Accel Y", newAccel[1]);
        }

        public void updateSensors(double[] wheelVelocity) {
            double[] newAccel = rawRobotAcceleration();
            double[] newAngularVelocity = robotAngularVelocityMagnitude();
            robotIMUVelocity.put(new IDouble(newAccel[2]), accelIntegrator.update(newAccel, wheelVelocity));
            robotAngularVelocity.put(new IDouble(newAngularVelocity[1]), new IDouble(newAngularVelocity[0]));
            robotAccelerations.put(new IDouble(newAccel[2]), new ITwist2d(newAccel[0], newAccel[1]));

            SmartDashboard.putNumber("raw Accel X", newAccel[0]);
            SmartDashboard.putNumber("raw Accel Y", newAccel[1]);
            // Logger.recordOutput("RobotState/raw Accel X", newAccel[0]);
            // Logger.recordOutput("RobotState/raw Accel Y", newAccel[1]);
        }

        public synchronized double robotYaw() {
            return pigeon.getYaw().getValueAsDouble();
        }

        /**
         * Gets the robot angular velocities from the pigeon
         *
         * @return double[] {AngularX, AngularY, Timestamp}
         */
        public synchronized double[] robotAngularVelocities(){
            double angularX = pigeon.getAngularVelocityXDevice().getValueAsDouble();
            double angularY = pigeon.getAngularVelocityYDevice().getValueAsDouble();

            double timestamp = pigeon.getAngularVelocityXDevice().getTimestamp().getTime();
            
            return new double[] {angularX, angularY, timestamp};
        }

        /**
         * Gets the robot angular magnitude from the pigeon
         *
         * @return double[] {AngularMagnitude, Timestamp}
         */
        public synchronized double[] robotAngularVelocityMagnitude(){
            double angularX = pigeon.getAngularVelocityXDevice().getValueAsDouble();
            double angularY = pigeon.getAngularVelocityYDevice().getValueAsDouble();

            double timestamp = pigeon.getAngularVelocityXDevice().getTimestamp().getTime();
            
            return new double[] {(Math.hypot(angularX, angularY)), timestamp};
        }

        /**
         * Gets the robot accelerations from the pigeon
         *
         * @return double[] {AccelerationX, AccelerationY, Timestamp}
         */
        public synchronized double[] rawRobotAcceleration() {
            double accelerationX = (pigeon.getAccelerationX().getValueAsDouble() - pigeon.getGravityVectorX().getValueAsDouble()) * 9.80665;
            double accelerationY = (pigeon.getAccelerationY().getValueAsDouble() - pigeon.getGravityVectorY().getValueAsDouble()) * 9.80665;
            
            double timestamp = pigeon.getAccelerationX().getTimestamp().getTime();
            SmartDashboard.putNumber("current timestamp", timestamp);
            // Logger.recordOutput("RobotState/current timestamp", timestamp);
            return new double[] {accelerationX, accelerationY, timestamp};
        }
 }