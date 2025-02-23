// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.commands.Slapdown.Pivot;

import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj2.command.Command;
import frc.robot.Subsystems.Slapdown;
import frc.robot.Subsystems.Slapdown.RollerState;

/* You should consider using the more terse Command factories API instead https://docs.wpilib.org/en/stable/docs/software/commandbased/organizing-command-based.html#defining-commands */
public class SmartIntake extends Command {
  private Slapdown s_Slapdown;
  Timer timer = new Timer();

  double previousSupplyCurrent = 0;
  
  public SmartIntake() {
    s_Slapdown = Slapdown.getInstance();
    addRequirements(s_Slapdown);
  }

  // Called when the command is initially scheduled.
  @Override
  public void initialize() 
  { 
    System.out.println("init");
    timer.restart();
    s_Slapdown.setRollerSpeed(RollerState.INTAKE.getRollerSpeed());
  }

  // Called every time the scheduler runs while the command is scheduled.
  @Override
  public void execute() {
    previousSupplyCurrent = s_Slapdown.getSupplyCurrent();
  }

  // Called once the command ends or is interrupted.
  @Override
  public void end(boolean interrupted) {
    System.out.println("End Smart Intake- break roller");
    s_Slapdown.brakeRoller();
  }


  // Returns true when the command should end.
  @Override
  public boolean isFinished() { //the timer should ensure we dont finish the command early as the motor overcomes static friction
    // return previousSupplyCurrent >= 8.2 && previousSupplyCurrent <= 8.4 &&  s_Slapdown.getSupplyCurrent() >= 8.2 && s_Slapdown.getSupplyCurrent() <= 8.4 && timer.hasElapsed(0.25);
    return s_Slapdown.getRollerVelocity() < 0.2 && timer.hasElapsed(0.25);
  }
}
